package io.github.kevinmf1.netlog.desktop;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.assertEquals;

public class DiscoveryTest {
    @Test public void discoversOnlyUniqueListenersNotAcceptedOrPendingSockets() {
        String table = "Num RefCount Protocol Flags Type St Inode Path\n"
            + "00000000: 00000002 00000000 00010000 0001 01 811 @netlog.v1.example.app.123\n"
            + "00000000: 00000002 00000000 00000000 0001 03 812 @netlog.v1.example.app.123\n"
            + "00000000: 00000002 00000000 00000000 0001 02 0 @netlog.v1.old.app.456\n"
            + "00000000: 00000002 00000000 00010000 0001 01 811 @netlog.v1.example.app.123\n"
            + "00000000: 00000002 00000000 00010000 0001 01 813 @other.socket\n"
            + "00000000: 00000002 00000000 00010000 0001 01 814 @netlog.v1.invalid;command\n";
        assertEquals(Set.of("netlog.v1.example.app.123"), AdbBridge.listeningSockets(table));
    }
}
