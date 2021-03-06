package ca.uwaterloo.cs.bigdata2016w.szmasood.assignment4;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.log4j.Logger;

import tl.lin.data.array.ArrayListOfFloatsWritable;
import tl.lin.data.array.ArrayListOfIntsWritable;

import com.google.common.base.Preconditions;


public class RunPersonalizedPageRankBasic extends Configured implements Tool {
    private static final Logger LOG = Logger.getLogger(RunPersonalizedPageRankBasic.class);

    private static final String SOURCE_NODES = "node.src";

    private static enum PageRank {
        nodes, edges, massMessages, massMessagesSaved, massMessagesReceived, missingStructure
    };

    // Mapper, no in-mapper combining.
    private static class MapClass extends
            Mapper<IntWritable, PageRankNode, IntWritable, PageRankNode> {


        // The neighbor to which we're sending messages.
        private static final IntWritable neighbor = new IntWritable();
        private static ArrayList<String> sourceNode = null;

        // Contents of the messages: partial PageRank mass.
        private static final PageRankNode intermediateMass = new PageRankNode();

        // For passing along node structure.
        private static final PageRankNode intermediateStructure = new PageRankNode();

        @Override
        public void setup(Mapper<IntWritable, PageRankNode, IntWritable, PageRankNode>.Context context) {
            String s = context.getConfiguration().get(SOURCE_NODES,"");
            String [] srces = s.split(",");
            sourceNode = new ArrayList<>();
            for (String sr: srces) {
                sourceNode.add(sr);
            }
        }

        @Override
        public void map(IntWritable nid, PageRankNode node, Context context)
                throws IOException, InterruptedException {
            // Pass along node structure.
            intermediateStructure.setNodeId(node.getNodeId());
            intermediateStructure.setType(PageRankNode.Type.Structure);
            intermediateStructure.setAdjacencyList(node.getAdjacenyList());

            context.write(nid, intermediateStructure);

            int massMessages = 0;

            // Distribute PageRank mass to neighbors (along outgoing edges).
            if (node.getAdjacenyList().size() > 0) {
                // Each neighbor gets an equal share of PageRank mass.
                ArrayListOfIntsWritable list = node.getAdjacenyList();
                ArrayListOfFloatsWritable f = node.getPageRank();

//                System.out.println ("F " + f.size() + " sourceNode " + sourceNode.size());
                float [] pr = new float[sourceNode.size()];
                for (int i =0; i < sourceNode.size(); i++) {
                    pr[i] = f.get(i) - (float) StrictMath.log(list.size());
                }

                context.getCounter(PageRank.edges).increment(list.size());

                // Iterate over neighbors.
                for (int i = 0; i < list.size(); i++) {
                    neighbor.set(list.get(i));
                    intermediateMass.setNodeId(list.get(i));
                    intermediateMass.setType(PageRankNode.Type.Mass);
                    intermediateMass.setPageRank(new ArrayListOfFloatsWritable(pr));

                    // Emit messages with PageRank mass to neighbors.
                    context.write(neighbor, intermediateMass);
                    massMessages++;
                }
            }

            // Bookkeeping.
            context.getCounter(PageRank.nodes).increment(1);
            context.getCounter(PageRank.massMessages).increment(massMessages);
        }

        @Override
        public void cleanup(Context context) throws IOException {
            sourceNode = null;
            neighbor.set(-1);
        }
    }


    // Combiner: sums partial PageRank contributions and passes node structure along.
    private static class CombineClass extends
            Reducer<IntWritable, PageRankNode, IntWritable, PageRankNode> {
        private static final PageRankNode intermediateMass = new PageRankNode();
        private static ArrayList<String> sourceNode = null;


        @Override
        public void setup(Context context) throws IOException {
            Configuration conf = context.getConfiguration();
            String s = conf.get(SOURCE_NODES,"");
            String [] srces = s.split(",");
            sourceNode = new ArrayList<>();
            for (String str : srces) {
                sourceNode.add(str);
            }
        }

        @Override
        public void reduce(IntWritable nid, Iterable<PageRankNode> values, Context context)
                throws IOException, InterruptedException {
            int massMessages = 0;

            // Remember, PageRank mass is stored as a log prob.

            float [] f = new float[sourceNode.size()];
            for (int i =0; i < sourceNode.size(); i++) {
                f[i] = Float.NEGATIVE_INFINITY;
            }
            for (PageRankNode n : values) {
                if (n.getType() == PageRankNode.Type.Structure) {
                    // Simply pass along node structure.
                    context.write(nid, n);
                } else {
                    // Accumulate PageRank mass contributions.
                    for (int i =0; i < sourceNode.size(); i++) {
                        f[i] = sumLogProbs(f[i], n.getPageRank().get(i));
                    }
                    massMessages++;
                }
            }

            // Emit aggregated results.
            if (massMessages > 0) {
                intermediateMass.setNodeId(nid.get());
                intermediateMass.setType(PageRankNode.Type.Mass);
                intermediateMass.setPageRank(new ArrayListOfFloatsWritable(f));

                context.write(nid, intermediateMass);
            }
        }

        @Override
        public void cleanup(Context context) throws IOException {
            sourceNode = null;
        }
    }

    // Reduce: sums incoming PageRank contributions, rewrite graph structure.
    private static class ReduceClass extends
            Reducer<IntWritable, PageRankNode, IntWritable, PageRankNode> {
        // For keeping track of PageRank mass encountered, so we can compute missing PageRank mass lost
        // through dangling nodes.
        private static ArrayList<Float> totalMass = null;
        private static ArrayList<String> sourceNode = null;

        @Override
        public void setup(Context context) throws IOException {
            Configuration conf = context.getConfiguration();
            String s = conf.get(SOURCE_NODES,"");
            String [] srces = s.split(",");
            totalMass = new ArrayList<>();
            sourceNode = new ArrayList<>();
            for (String str : srces) {
                sourceNode.add(str);
                totalMass.add(Float.NEGATIVE_INFINITY);
            }
        }

        @Override
        public void reduce(IntWritable nid, Iterable<PageRankNode> iterable, Context context)
                throws IOException, InterruptedException {
            Iterator<PageRankNode> values = iterable.iterator();

            // Create the node structure that we're going to assemble back together from shuffled pieces.
            PageRankNode node = new PageRankNode();

            node.setType(PageRankNode.Type.Complete);
            node.setNodeId(nid.get());

            int massMessagesReceived = 0;
            int structureReceived = 0;


            float [] f = new float[sourceNode.size()];
            for (int i =0; i < sourceNode.size(); i++) {
                f[i] = Float.NEGATIVE_INFINITY;
            }

            while (values.hasNext()) {
                PageRankNode n = values.next();

                if (n.getType().equals(PageRankNode.Type.Structure)) {
                    // This is the structure; update accordingly.
                    ArrayListOfIntsWritable list = n.getAdjacenyList();
                    structureReceived++;

                    node.setAdjacencyList(list);
                } else {
                    // This is a message that contains PageRank mass; accumulate.
                    for (int i =0; i < sourceNode.size(); i++) {
                        f[i] = sumLogProbs(f[i], n.getPageRank().get(i));
                    }
                    massMessagesReceived++;
                }
            }

            // Update the final accumulated PageRank mass.
            node.setPageRank(new ArrayListOfFloatsWritable(f));
            context.getCounter(PageRank.massMessagesReceived).increment(massMessagesReceived);

            // Error checking.
            if (structureReceived == 1) {
                // Everything checks out, emit final node structure with updated PageRank value.
                context.write(nid, node);
                for (int i =0; i < sourceNode.size(); i++) {
                    totalMass.set(i, sumLogProbs(totalMass.get(i),f[i]));
                }
            } else if (structureReceived == 0) {
                // We get into this situation if there exists an edge pointing to a node which has no
                // corresponding node structure (i.e., PageRank mass was passed to a non-existent node)...
                // log and count but move on.
                context.getCounter(PageRank.missingStructure).increment(1);
                LOG.warn("No structure received for nodeid: " + nid.get() + " mass: "
                        + massMessagesReceived);
                // It's important to note that we don't add the PageRank mass to total... if PageRank mass
                // was sent to a non-existent node, it should simply vanish.
            } else {
                // This shouldn't happen!
                throw new RuntimeException("Multiple structure received for nodeid: " + nid.get()
                        + " mass: " + massMessagesReceived + " struct: " + structureReceived);
            }
        }

        @Override
        public void cleanup(Context context) throws IOException {
            Configuration conf = context.getConfiguration();
            String taskId = conf.get("mapred.task.id");
            String path = conf.get("PageRankMassPath");

            Preconditions.checkNotNull(taskId);
            Preconditions.checkNotNull(path);

            // Write to a file the amount of PageRank mass we've seen in this reducer.
            FileSystem fs = FileSystem.get(context.getConfiguration());
            FSDataOutputStream out = fs.create(new Path(path + "/" + taskId), false);

            for (int i=0; i < sourceNode.size(); i++) {
//                System.out.println ("Phase 1 cleanup " + totalMass.get(i));
                out.writeFloat(totalMass.get(i));
            }

            out.close();

            totalMass = null;
            sourceNode = null;
        }
    }

    // Mapper that distributes the missing PageRank mass (lost at the dangling nodes) and takes care
    // of the random jump factor.
    private static class MapPageRankMassDistributionClass extends
            Mapper<IntWritable, PageRankNode, IntWritable, PageRankNode> {
        private float [] missingMass = null;
        private int nodeCnt = 0;
        private static ArrayList<String> sourceNode = null;

        @Override
        public void setup(Context context) throws IOException {
            Configuration conf = context.getConfiguration();

            nodeCnt = conf.getInt("NodeCount", 0);
            sourceNode = new ArrayList<>();
            String s = conf.get(SOURCE_NODES,"");
            String [] srces = s.split(",");
            for (String sr : srces) {
                sourceNode.add(sr);
            }

            missingMass = new float[srces.length];
//            System.out.println (" Phase 2 setup: " + conf.get("MissingMass", ""));
            String [] mMass= conf.get("MissingMass", "").split("\\|");
            for (int i=0; i < srces.length; i++) {
//                System.out.println ("mMass " + mMass[i]);
                missingMass[i] = Float.parseFloat(mMass[i]);
            }
        }


        @Override
        public void map(IntWritable nid, PageRankNode node, Context context)
                throws IOException, InterruptedException {

            float [] f = new float [sourceNode.size()];

            for (int i =0; i < sourceNode.size(); i++) {
                f[i] = node.getPageRank().get(i);
            }

            float jump = Float.NEGATIVE_INFINITY;
            float link = Float.NEGATIVE_INFINITY;

            int ind = sourceNode.indexOf(String.valueOf(node.getNodeId()));
            for (int i=0; i < sourceNode.size(); i++) {
                if (i == ind) {
                    jump =  (float) Math.log(ALPHA);
                    link =  (float) Math.log(1.0f - ALPHA) + sumLogProbs(f[i], (float) (Math.log(missingMass[i])));
                    f[i] = sumLogProbs(jump,link);
                }
                else {
                    link =  (float) Math.log(1.0f - ALPHA);
                    f[i] += link;
                }
            }

            node.setPageRank(new ArrayListOfFloatsWritable(f));
            context.write(nid, node);
        }

        @Override
        public void cleanup(Context context) throws IOException {
            missingMass = null;
            sourceNode = null;
        }
    }

    // Random jump factor.
    private static float ALPHA = 0.15f;
    private static NumberFormat formatter = new DecimalFormat("0000");

    /**
     * Dispatches command-line arguments to the tool via the {@code ToolRunner}.
     */
    public static void main(String[] args) throws Exception {
        ToolRunner.run(new RunPersonalizedPageRankBasic(), args);
    }

    public RunPersonalizedPageRankBasic() {}

    private static final String BASE = "base";
    private static final String NUM_NODES = "numNodes";
    private static final String SOURCES = "sources";
    private static final String START = "start";
    private static final String END = "end";
    private static final String COMBINER = "useCombiner";


    /**
     * Runs this tool.
     */
    @SuppressWarnings({ "static-access" })
    public int run(String[] args) throws Exception {
        Options options = new Options();

        options.addOption(new Option(COMBINER, "use combiner"));

        options.addOption(OptionBuilder.withArgName("path").hasArg()
                .withDescription("base path").create(BASE));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("start iteration").create(START));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("end iteration").create(END));
        options.addOption(OptionBuilder.withArgName("num").hasArg()
                .withDescription("number of nodes").create(NUM_NODES));
        options.addOption(OptionBuilder.withArgName("sources").hasArg()
                .withDescription("Sources").create(SOURCES));

        CommandLine cmdline;
        CommandLineParser parser = new GnuParser();

        try {
            cmdline = parser.parse(options, args);
        } catch (ParseException exp) {
            System.err.println("Error parsing command line: " + exp.getMessage());
            return -1;
        }

        if (!cmdline.hasOption(BASE) || !cmdline.hasOption(START) ||
                !cmdline.hasOption(END) || !cmdline.hasOption(NUM_NODES) || !cmdline.hasOption(SOURCES)) {
            System.out.println("args: " + Arrays.toString(args));
            HelpFormatter formatter = new HelpFormatter();
            formatter.setWidth(120);
            formatter.printHelp(this.getClass().getName(), options);
            ToolRunner.printGenericCommandUsage(System.out);
            return -1;
        }

        String basePath = cmdline.getOptionValue(BASE);
        int n = Integer.parseInt(cmdline.getOptionValue(NUM_NODES));
        int s = Integer.parseInt(cmdline.getOptionValue(START));
        int e = Integer.parseInt(cmdline.getOptionValue(END));
        boolean useCombiner = cmdline.hasOption(COMBINER);
        String srces = cmdline.getOptionValue(SOURCES);

        LOG.info("Tool name: RunPageRank");
        LOG.info(" - base path: " + basePath);
        LOG.info(" - num nodes: " + n);
        LOG.info(" - start iteration: " + s);
        LOG.info(" - end iteration: " + e);
        LOG.info(" - use combiner: " + useCombiner);
        LOG.info(" - sources: " + srces);

        // Iterate PageRank.
        for (int i = s; i < e; i++) {
            iteratePageRank(i, i + 1, basePath, n, useCombiner, srces);
        }

        return 0;
    }

    // Run each iteration.
    private void iteratePageRank(int i, int j, String basePath, int numNodes,
                                 boolean useCombiner, String srces) throws Exception {
        // Each iteration consists of two phases (two MapReduce jobs).

        // Job 1: distribute PageRank mass along outgoing edges.
        float [] mass = phase1(i, j, basePath, numNodes, useCombiner, srces);

        // Find out how much PageRank mass got lost at the dangling nodes.
        float [] missing = new float [mass.length];
        for (int z =0; z < mass.length; z++) {
            missing[z] = 1.0f - (float)StrictMath.exp(mass[z]);
            if (missing[z] < 0) {
                missing[z] = 0.0f;
            }
//            System.out.println ("IterPageRank " + missing[z]);
        }
        // Job 2: distribute missing mass, take care of random jump factor.
        phase2(i, j, missing, basePath, numNodes, srces);
    }

    private float [] phase1(int i, int j, String basePath, int numNodes,
                         boolean useCombiner, String srces) throws Exception {

        Configuration conf = getConf();
        conf.set(SOURCE_NODES, srces);


        Job job = Job.getInstance(conf);
        job.setJobName("PageRank:Basic:iteration" + j + ":Phase1");
        job.setJarByClass(RunPersonalizedPageRankBasic.class);

        String in = basePath + "/iter" + formatter.format(i);
        String out = basePath + "/iter" + formatter.format(j) + "t";
        String outm = out + "-mass";

        // We need to actually count the number of part files to get the number of partitions (because
        // the directory might contain _log).
        int numPartitions = 0;
        for (FileStatus s : FileSystem.get(getConf()).listStatus(new Path(in))) {
            if (s.getPath().getName().contains("part-"))
                numPartitions++;
        }

        LOG.info("PageRank: iteration " + j + ": Phase1");
        LOG.info(" - input: " + in);
        LOG.info(" - output: " + out);
        LOG.info(" - nodeCnt: " + numNodes);
        LOG.info(" - useCombiner: " + useCombiner);
        LOG.info("computed number of partitions: " + numPartitions);

        int numReduceTasks = numPartitions;

        job.getConfiguration().setInt("NodeCount", numNodes);
        job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
        job.getConfiguration().setBoolean("mapred.reduce.tasks.speculative.execution", false);
        //job.getConfiguration().set("mapred.child.java.opts", "-Xmx2048m");
        job.getConfiguration().set("PageRankMassPath", outm);

        job.setNumReduceTasks(numReduceTasks);

        FileInputFormat.setInputPaths(job, new Path(in));
        FileOutputFormat.setOutputPath(job, new Path(out));

        job.setInputFormatClass(NonSplitableSequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(PageRankNode.class);

        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(PageRankNode.class);

        job.setMapperClass(MapClass.class);

        if (useCombiner) {
            job.setCombinerClass(CombineClass.class);
        }

        job.setReducerClass(ReduceClass.class);

        FileSystem.get(getConf()).delete(new Path(out), true);
        FileSystem.get(getConf()).delete(new Path(outm), true);

        long startTime = System.currentTimeMillis();
        job.waitForCompletion(true);
        System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

        String [] s = srces.split(",");
        float [] mass = new float[s.length];
        for (int w =0; w < s.length; w++) {
            mass[w] = Float.NEGATIVE_INFINITY;
        }

//        System.out.println ("Phase 1 size " + mass.length);
        FileSystem fs = FileSystem.get(getConf());
        float rF = 0.0f;
        for (FileStatus f : fs.listStatus(new Path(outm))) {
            FSDataInputStream fin = fs.open(f.getPath());
            for (int q =0; q < mass.length; q++) {
                rF = fin.readFloat();
//                System.out.println ("MASS PHASE 1 " + rF);
                mass[q] = sumLogProbs(mass[q], rF);

            }
            fin.close();
        }

        return mass;
    }

    private void phase2(int i, int j, float [] missing, String basePath, int numNodes, String srces) throws Exception {

        Configuration conf = getConf();
        conf.set(SOURCE_NODES, srces);

        Job job = Job.getInstance(conf);
        job.setJobName("PageRank:Basic:iteration" + j + ":Phase2");
        job.setJarByClass(RunPersonalizedPageRankBasic.class);

        StringBuilder sb = new StringBuilder();
        for (int m=0; m < missing.length; m++) {
            sb.append(missing[m]);
            if (m != missing.length - 1) {
                sb.append("|");
            }
        }
//        System.out.println (sb.toString());
        LOG.info("missing PageRank mass: " + sb.toString());
        LOG.info("number of nodes: " + numNodes);

        String in = basePath + "/iter" + formatter.format(j) + "t";
        String out = basePath + "/iter" + formatter.format(j);

        LOG.info("PageRank: iteration " + j + ": Phase2");
        LOG.info(" - input: " + in);
        LOG.info(" - output: " + out);

        job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
        job.getConfiguration().setBoolean("mapred.reduce.tasks.speculative.execution", false);
        job.getConfiguration().set("MissingMass", sb.toString());

        job.getConfiguration().setInt("NodeCount", numNodes);

        job.setNumReduceTasks(0);

        FileInputFormat.setInputPaths(job, new Path(in));
        FileOutputFormat.setOutputPath(job, new Path(out));

        job.setInputFormatClass(NonSplitableSequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(PageRankNode.class);

        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(PageRankNode.class);

        job.setMapperClass(MapPageRankMassDistributionClass.class);

        FileSystem.get(getConf()).delete(new Path(out), true);

        long startTime = System.currentTimeMillis();
        job.waitForCompletion(true);
        System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
    }

    // Adds two log probs.
    private static float sumLogProbs(float a, float b) {
        if (a == Float.NEGATIVE_INFINITY)
            return b;

        if (b == Float.NEGATIVE_INFINITY)
            return a;

        if (a < b) {
            return (float) (b + StrictMath.log1p(StrictMath.exp(a - b)));
        }

        return (float) (a + StrictMath.log1p(StrictMath.exp(b - a)));
    }
}
