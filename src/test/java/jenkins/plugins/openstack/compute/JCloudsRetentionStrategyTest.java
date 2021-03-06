package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import hudson.util.OneShotEvent;
import jenkins.plugins.openstack.PluginTestRule;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.TestExtension;

import java.io.IOException;

public class JCloudsRetentionStrategyTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    @Test
    public void scheduleSlaveDelete() throws Exception {
        int retentionTime = 1; // minute

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "template", "label", SlaveOptions.builder().retentionTime(retentionTime).build()
        );

        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(template));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        assertEquals(1, (int) slave.getSlaveOptions().getRetentionTime());

        JCloudsRetentionStrategy strategy = (JCloudsRetentionStrategy) slave.getRetentionStrategy();
        strategy.check(computer);
        assertFalse("Slave should not be scheduled for deletion right away", computer.isPendingDelete());

        Thread.sleep(1000 * 61); // Wait for the slave to be idle long enough

        strategy.check(computer);
        assertTrue("Slave should be scheduled for deletion", computer.isPendingDelete());
    }

    /**
     * There are several async operations taking place here:
     *
     * agent launch - in this case it JNLP noop implementation
     * slave connection activity - wrapper of agent launch with listeners called
     * openstack provisioning - that wait until the slave connection activity is done
     *
     * This tests simulates prolonged agent launch inserting delay in one of the listeners (in slave connection activity)
     * as we can not replace agent launch impl easily.
     *
     * @throws Exception
     */
    @Test
    public void doNotDeleteTheSlaveWhileLaunching() throws Exception {
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(j.dummySlaveTemplate(
                j.dummySlaveOptions().getBuilder().retentionTime(0) // disposable immediately
                        .startTimeout(3000) // give up soon enough to speed the test up
                        .build(),
                "label"
        )));
        JCloudsSlave node = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) node.toComputer();
        assertSame(computer, j.jenkins.getComputer(node.getNodeName()));
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        computer.getRetentionStrategy().check(computer);

        // Still connecting after retention strategy run
        computer = getNodeFor(node.getId());
        assertFalse(computer.isPendingDelete());
        assertTrue(computer.isConnecting());

        LaunchBlocker.unlock.signal();
    }

    // Wait for the future to create computer
    private JCloudsComputer getNodeFor(ProvisioningActivity.Id id) throws InterruptedException {
        while (true) {
            for (Computer c: j.jenkins.getComputers()) {
                if (c instanceof JCloudsComputer) {
                    if (((JCloudsComputer) c).getId().equals(id)) {
                        return (JCloudsComputer) c;
                    }
                }
            }
            Thread.sleep(100);
        }
    }

    @TestExtension("doNotDeleteTheSlaveWhileLaunching")
    public static class LaunchBlocker extends ComputerListener {
        private static OneShotEvent unlock = new OneShotEvent();
        @Override
        public void preLaunch(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            unlock.block();
        }
    }

    @Test
    public void doNotDeleteSlavePutOfflineByUser() throws Exception {
        JCloudsCloud cloud = j.configureSlaveLaunching(j.dummyCloud(j.dummySlaveTemplate(
                // no retention to make the slave disposable w.r.t retention time
                j.dummySlaveOptions().getBuilder().retentionTime(0).build(),
                "label"
        )));
        JCloudsSlave slave = j.provision(cloud, "label");
        JCloudsComputer computer = (JCloudsComputer) slave.toComputer();
        computer.waitUntilOnline();
        computer.setTemporarilyOffline(true, new OfflineCause.UserCause(User.current(), "Offline"));

        computer.getRetentionStrategy().check(computer);
        assertFalse(computer.isPendingDelete());

        computer.setTemporarilyOffline(false, null);

        computer.getRetentionStrategy().check(computer);
        assertTrue(computer.isPendingDelete());
    }
}
