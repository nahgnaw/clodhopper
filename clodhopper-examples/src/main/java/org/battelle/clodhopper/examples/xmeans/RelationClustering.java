package org.battelle.clodhopper.examples.xmeans;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.io.File;
import java.util.concurrent.ExecutionException;

import org.battelle.clodhopper.Cluster;
import org.battelle.clodhopper.ClusterStats;
import org.battelle.clodhopper.Clusterer;
import org.battelle.clodhopper.distance.DistanceMetric;
import org.battelle.clodhopper.examples.TupleGenerator;
import org.battelle.clodhopper.seeding.ClusterSeeder;
import org.battelle.clodhopper.seeding.PreassignedSeeder;
import org.battelle.clodhopper.task.*;
import org.battelle.clodhopper.tuple.ArrayTupleList;
import org.battelle.clodhopper.tuple.TupleList;
import org.battelle.clodhopper.tuple.TupleMath;
import org.battelle.clodhopper.util.ArrayIntIterator;
import org.battelle.clodhopper.xmeans.XMeansClusterer;
import org.battelle.clodhopper.xmeans.XMeansParams;


public class RelationClustering {

    /**
     * Create two cluster seeds by going +/- one standard deviation from the
     * cluster's center.
     *
     * @param cluster the cluster of concern.
     *
     * @return TupleList containing two seeds
     */
    private static TupleList createTwoSeeds(TupleList tuples, final Cluster cluster) {

        int dim = tuples.getTupleLength();

        double[][] stats = ClusterStats.computeMeanAndVariance(tuples, cluster);

        TupleList seeds = new ArrayTupleList(dim, 2);

        double[] seed1 = new double[dim];
        double[] seed2 = new double[dim];

        for (int i = 0; i < dim; i++) {
            double center = stats[i][0];
            double sdev = Math.sqrt(stats[i][1]);
            seed1[i] = center - sdev;
            seed2[i] = center + sdev;
        }

        seeds.setTuple(0, seed1);
        seeds.setTuple(1, seed2);

        return seeds;
    }

    public static void main(String[] args) {

        int minClusterNo = 1;
        int maxClusterNo = Integer.MAX_VALUE;

        int tupleLength = 200;
        int tupleCount = 0;
        List<Double> dataArrayList = new ArrayList<> ();

        String dataset = "genes-cancer";
//        String dataset = "RiMG75";
        String embedding_file = "/Users/HanWang/Workspace/sci-kb/data/" + dataset + "/subj_obj_embeddings.txt";

        try {
            Scanner EmbeddingFile =
                    new Scanner(new File(embedding_file));
            while(EmbeddingFile.hasNextLine()){
                String line = EmbeddingFile.nextLine();
                Scanner scanner = new Scanner(line);
                scanner.useDelimiter(" ");
                while(scanner.hasNextDouble()){
                    dataArrayList.add(scanner.nextDouble());
                }
                scanner.close();
                tupleCount++;
            }
            EmbeddingFile.close();
        } catch (Exception e) {
            System.err.println("Exception: " + e.getMessage());
        }

        double[] data = new double[tupleCount * tupleLength];
        for (int i = 0; i < tupleCount * tupleLength; i++) {
            data[i] = dataArrayList.get(i);
        }

        // Wrap the data in an ArrayTupleList.
        TupleList tupleData = new ArrayTupleList(tupleLength, tupleCount, data);

        // Initial seed
        int[] allIDs = new int[tupleCount];
        for (int i = 0; i < tupleCount; i++) {
            allIDs[i] = i;
        }
        double[] center = TupleMath.average(tupleData, new ArrayIntIterator(allIDs));
        Cluster cluster = new Cluster(allIDs, center);
        TupleList initSeeds = createTwoSeeds(tupleData, cluster);

        // Construct the parameters.
        XMeansParams.Builder builder = new XMeansParams.Builder();
        XMeansParams params = builder.minClusters(minClusterNo)
                                     .maxClusters(maxClusterNo)
                                     .minClusterToMeanThreshold(0)
                                     .clusterSeeder(new PreassignedSeeder(initSeeds))
                                     .build();

        // Display the default xmeans parameters.
        //
        ClusterSeeder seeder = params.getClusterSeeder();
        DistanceMetric distMetric = params.getDistanceMetric();
        int maxClusters = params.getMaxClusters();
        int minClusters = params.getMinClusters();
        double minClusterToMeanThreshold = params.getMinClusterToMeanThreshold();
        boolean useOverallBIC = params.getUseOverallBIC();
        int workerThreadCount = params.getWorkerThreadCount();

        System.out.println("XMeans Default Parameters:\n");
        System.out.printf("\tCluster seeding type = %s\n", seeder.getClass().getSimpleName());
        System.out.printf("\tDistance metric = %s\n", distMetric.getClass().getSimpleName());
        System.out.printf("\tmin clusters = %d, max clusters = %d\n", minClusters, maxClusters);
        System.out.printf("\tmin cluster to mean threshold = %f\n", minClusterToMeanThreshold);
        System.out.printf("\tUsing overall BIC = %s\n", String.valueOf(useOverallBIC));
        System.out.printf("\tWorker thread count = %d\n\n", workerThreadCount);

        // Construct the XMeansClusterer
        Clusterer xmeans = new XMeansClusterer(tupleData, params);

        // Add a listener for task life cycle events.
        xmeans.addTaskListener(new TaskListener() {

            @Override
            public void taskBegun(TaskEvent e) {
                System.out.printf("%s\n\n", e.getMessage());
            }

            @Override
            public void taskMessage(TaskEvent e) {
                System.out.println("  ... " + e.getMessage());
            }

            @Override
            public void taskProgress(TaskEvent e) {
                // Reports the progress.  Ignore for this example.
            }

            @Override
            public void taskPaused(TaskEvent e) {
                // Reports that the task has been paused. Won't happen in this example,
                // so ignore.
            }

            @Override
            public void taskResumed(TaskEvent e) {
                // Reports when a paused task has been resumed.
                // Ignore for this example.
            }

            @Override
            public void taskEnded(TaskEvent e) {
                // Reports when a task has finished regardless of whether the task succeeded or
                // failed. Check the task outcome to know if it succeeded.
                System.out.printf("\n%s\n\n", e.getMessage());
            }

        });

        // Launch xmeans on another thread.
        Thread t = new Thread(xmeans);
        t.start();

        List<Cluster> clusters = null;

        try {

            // Since a Clusterer implements Future<List<Cluster>>, it has a blocking get() method.
            clusters = xmeans.get();

            // Don't have to worry about these, because the task outcome will tell you what happened.
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        } catch (ExecutionException e2) {
            e2.printStackTrace();
        }

        if (xmeans.getTaskOutcome() == TaskOutcome.SUCCESS) {
            System.out.println("XMeans was successful!");
        } else if (xmeans.getTaskOutcome() == TaskOutcome.ERROR) {
            System.out.println("XMeans failed with the error: " + xmeans.getErrorMessage());
        } else if (xmeans.getTaskOutcome() == TaskOutcome.CANCELLED) {
            System.out.println("XMeans was somehow canceled, even though this method doesn't provide a path to cancellation.");
        } else {
            System.out.println("XMeans finished with unexpected outcome " + xmeans.getTaskOutcome() + ": please submit a bug report!");
        }

        if (clusters != null) {
            // May not be the same as clusterCount, since XMeans attempted to statistically discern the distribution.
            final int xmeansClusterCount = clusters.size();
            System.out.printf("\nXMeans Generated %d Clusters\n", xmeansClusterCount);

            try {
                File clusterFile = new File("/Users/HanWang/Workspace/sci-kb/data/" + dataset + "/clusters.txt");
                if (!clusterFile.exists()) {
                    clusterFile.createNewFile();
                }
                FileWriter wr = new FileWriter(clusterFile);
                int memberCount = 0;
                for (int i = 0; i < xmeansClusterCount; i++) {
                    Cluster c = clusters.get(i);
                    int clusterSize = c.getMemberCount();
                    int[] clusterMembers = new int[clusterSize];
                    for (int j = 0; j < clusterSize; j++) {
                        clusterMembers[j] = c.getMember(j);
                        wr.write(clusterMembers[j] + " ");
                        memberCount++;
                    }
                    wr.write(System.getProperty("line.separator"));
                    System.out.printf("Cluster %d: size = %d, members = %s\n", (i + 1), clusterSize, Arrays.toString(clusterMembers));
                }
                wr.flush();
                wr.close();
                System.out.printf("Total data point: %d\n", memberCount);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
