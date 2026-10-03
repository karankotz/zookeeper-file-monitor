package com.karankotz.filemonitor.dev;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingCluster;

/**
 * Local infrastructure without Docker: a real 3-server ZooKeeper ensemble
 * (localhost:2181-2183) and DynamoDB Local (http://localhost:8000), all in one JVM.
 *
 * <pre>
 * mvn -q test-compile exec:java@local-infra
 * </pre>
 */
public final class LocalInfra {

    public static void main(String[] args) throws Exception {
        List<InstanceSpec> specs = List.of(
                new InstanceSpec(null, 2181, 3881, 2881, true, 1),
                new InstanceSpec(null, 2182, 3882, 2882, true, 2),
                new InstanceSpec(null, 2183, 3883, 2883, true, 3));
        TestingCluster zookeeper = new TestingCluster(specs);
        zookeeper.start();

        DynamoDBProxyServer dynamo = startDynamoDbLocal(8000);

        System.out.println();
        System.out.println("ZooKeeper ensemble : " + zookeeper.getConnectString());
        System.out.println("DynamoDB Local     : http://localhost:8000");
        System.out.println("Press Ctrl+C to stop.");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                dynamo.stop();
                zookeeper.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            stopped.countDown();
        }));
        stopped.await();
    }

    public static DynamoDBProxyServer startDynamoDbLocal(int port) throws Exception {
        DynamoDBProxyServer server = ServerRunner.createServerFromCommandLineArgs(
                new String[] {"-inMemory", "-port", Integer.toString(port)});
        server.start();
        return server;
    }
}
