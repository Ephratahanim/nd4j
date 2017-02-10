package org.nd4j.parameterserver.node;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import org.junit.BeforeClass;
import org.junit.Test;
import org.nd4j.aeron.ipc.AeronUtil;
import org.nd4j.aeron.ipc.NDArrayMessage;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.client.ParameterServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Created by agibsonccc on 12/3/16.
 */
public class ParameterServerNodeTest {
    private static MediaDriver mediaDriver;
    private static Logger log = LoggerFactory.getLogger(ParameterServerNodeTest.class);
    private static Aeron aeron;
    private static ParameterServerNode parameterServerNode;
    private static int parameterLength = 4;
    private static int masterStatusPort = 40323 + new java.util.Random().nextInt(15999);
    private static int statusPort = masterStatusPort - 1299;

    @BeforeClass
    public static void before() throws Exception {
        mediaDriver = MediaDriver.launchEmbedded(AeronUtil.getMediaDriverContext(parameterLength));
        System.setProperty("play.server.dir","/tmp");
        aeron = Aeron.connect(getContext());
        parameterServerNode = new ParameterServerNode(mediaDriver,statusPort);
        parameterServerNode.runMain(new String[] {
                "-m","true",
                "-s","1," + String.valueOf(parameterLength),
                "-p",String.valueOf(masterStatusPort),
                "-h","localhost",
                "-id","11",
                "-md", mediaDriver.aeronDirectoryName(),
                "-sp", String.valueOf(statusPort),
                "-sh","localhost",
                "-u",String.valueOf(Runtime.getRuntime().availableProcessors())
        });

        while(!parameterServerNode.subscriberLaunched()) {
            Thread.sleep(10000);
        }

    }

    @Test
    public void testSimulateRun() throws Exception {
        int numCores = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(numCores);
        ParameterServerClient[] clients = new ParameterServerClient[numCores];
        String host = "localhost";
        for(int i = 0; i < numCores; i++) {
            clients[i] = ParameterServerClient.builder()
                    .aeron(aeron).masterStatusHost(host)
                    .masterStatusPort(statusPort).subscriberHost(host).subscriberPort(40325 + i).subscriberStream(10 + i)
                    .ndarrayRetrieveUrl(parameterServerNode.getSubscriber()[i].getResponder().connectionUrl())
                    .ndarraySendUrl(parameterServerNode.getSubscriber()[i].getSubscriber().connectionUrl())
                    .build();
        }

        Thread.sleep(60000);

        //no arrays have been sent yet
        for(int i = 0; i < numCores; i++) {
            assertFalse(clients[i].isReadyForNext());
        }

        //send "numCores" arrays, the default parameter server updater
        //is synchronous so it should be "ready" when number of updates == number of workers
        for(int i = 0; i < numCores; i++) {
            clients[i].pushNDArrayMessage(NDArrayMessage.wholeArrayUpdate(Nd4j.ones(parameterLength)));
        }

        Thread.sleep(10000);

        //all arrays should have been sent
        for(int i = 0; i < numCores; i++) {
            assertTrue(clients[i].isReadyForNext());
        }

        Thread.sleep(10000);

        for(int i = 0; i < 1; i++) {
            assertEquals(Nd4j.valueArrayOf(1,parameterLength,4),clients[i].getArray());
            Thread.sleep(1000);
        }

        executorService.shutdown();

        Thread.sleep(60000);

        parameterServerNode.close();


    }


    private static  Aeron.Context getContext() {
        return new Aeron.Context().publicationConnectionTimeout(-1)
                .availableImageHandler(AeronUtil::printAvailableImage)
                .unavailableImageHandler(AeronUtil::printUnavailableImage)
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()).keepAliveInterval(1000)
                .errorHandler(e -> log.error(e.toString(), e));
    }


}
