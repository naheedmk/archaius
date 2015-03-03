package netflix.archaius.config;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import netflix.archaius.Config;
import netflix.archaius.DynamicConfigObserver;
import netflix.archaius.config.polling.ManualPollingStrategy;
import netflix.archaius.junit.TestHttpServer;
import netflix.archaius.property.PropertiesServerHandler;
import netflix.archaius.readers.URLConfigReader;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PollingDynamicConfigTest {
    
    private PropertiesServerHandler prop1 = new PropertiesServerHandler();
    private PropertiesServerHandler prop2 = new PropertiesServerHandler();
    
    @Rule
    public TestHttpServer server = new TestHttpServer()
            .handler("/prop1", prop1)
            .handler("/prop2", prop2)
            ;
    
    @Test(timeout=1000)
    public void testBasicRead() throws Exception {
        URLConfigReader reader = new URLConfigReader(
                server.getServerPathURI("/prop1").toURL()
                );
        
        Map<String, Object> result;
        
        prop1.setProperty("a", "a_value");
        result = reader.call();
        Assert.assertFalse(result.isEmpty());
        Assert.assertEquals("a_value", result.get("a"));
        
        prop1.setProperty("a", "b_value");
        result = reader.call();
        Assert.assertFalse(result.isEmpty());
        Assert.assertEquals("b_value", result.get("a"));
    }

    @Test(timeout=1000)
    public void testCombineSources() throws Exception {
        URLConfigReader reader = new URLConfigReader(
                server.getServerPathURI("/prop1").toURL(),
                server.getServerPathURI("/prop2").toURL()
                );
        
        Assert.assertTrue(prop1.isEmpty());
        Assert.assertTrue(prop2.isEmpty());
        
        prop1.setProperty("a", "A");
        prop2.setProperty("b", "B");
        
        Map<String, Object> result = reader.call();

        Assert.assertEquals(2, result.size());
        Assert.assertEquals("A", result.get("a"));
        Assert.assertEquals("B", result.get("b"));
    }
    
    @Test(timeout=1000, expected=IOException.class)
    public void testFailure() throws Exception {
        URLConfigReader reader = new URLConfigReader(
                server.getServerPathURI("/prop1").toURL());
        
        prop1.setResponseCode(500);
        reader.call();
        
        Assert.fail("Should have failed with 500 error");
    }
    
    @Test(timeout=1000)
    public void testDynamicConfig() throws Exception {
        URLConfigReader reader = new URLConfigReader(
                server.getServerPathURI("/prop1").toURL(),
                server.getServerPathURI("/prop2").toURL()
                );

        ManualPollingStrategy strategy = new ManualPollingStrategy();
        PollingDynamicConfig config = new PollingDynamicConfig(null, reader, strategy);
        
        // Initialize
        //  a=A
        //  b=B
        prop1.setProperty("a", "A");
        prop2.setProperty("b", "B");
        
        strategy.fire();
        
        // Modify
        //  a=ANew
        //  b=BNew
        Assert.assertFalse(config.isEmpty());
        Assert.assertEquals("A", config.getString("a"));
        Assert.assertEquals("B", config.getString("b"));
        
        prop1.setProperty("a", "ANew");
        prop2.setProperty("b", "BNew");
        Assert.assertEquals("A", config.getString("a"));
        Assert.assertEquals("B", config.getString("b"));
        
        // Delete 1
        //  a deleted
        //  b=BNew
        strategy.fire();
        Assert.assertEquals("ANew", config.getString("a"));
        Assert.assertEquals("BNew", config.getString("b"));

        prop1.remove("a");
        prop2.setProperty("b", "BNew");
        
        strategy.fire();
        Assert.assertNull(config.getString("a", null));
        Assert.assertEquals("BNew", config.getString("b"));
    }
    
    @Test(timeout=1000)
    public void testDynamicConfigFailures() throws Exception {
        URLConfigReader reader = new URLConfigReader(
                server.getServerPathURI("/prop1").toURL(),
                server.getServerPathURI("/prop2").toURL()
                );

        ManualPollingStrategy strategy = new ManualPollingStrategy();
        PollingDynamicConfig config = new PollingDynamicConfig(null, reader, strategy);
        
        final AtomicInteger errorCount = new AtomicInteger();
        final AtomicInteger updateCount = new AtomicInteger();
        
        config.addListener(new DynamicConfigObserver() {
            @Override
            public void onUpdate(String propName, Config config) {
            }

            @Override
            public void onUpdate(Config config) {
                updateCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable error, Config config) {
                errorCount.incrementAndGet();
            }
        });
        
        // Confirm success on first pass
        prop1.setProperty("a", "A");
        
        strategy.fire();
        
        Assert.assertEquals("A", config.getString("a"));
        Assert.assertEquals(0, errorCount.get());
        Assert.assertEquals(1, updateCount.get());

        // Confirm failure does not modify state of Config
        prop1.setProperty("a", "ANew");
        prop1.setResponseCode(500);

        strategy.fire();
        
        Assert.assertEquals(1, errorCount.get());
        Assert.assertEquals(1, updateCount.get());
        Assert.assertEquals("A", config.getString("a"));

        // Confim state updates after failure
        prop1.setResponseCode(200);
        
        strategy.fire();
        
        Assert.assertEquals(1, errorCount.get());
        Assert.assertEquals(2, updateCount.get());
        Assert.assertEquals("ANew", config.getString("a"));
    }
}
