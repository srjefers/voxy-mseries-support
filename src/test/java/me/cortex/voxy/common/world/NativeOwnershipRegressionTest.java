package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import org.rocksdb.*;
import java.util.List;
import java.util.ArrayList;
import static org.mockito.Mockito.*;

/** Exercises the real constructor with native allocation boundaries mocked. */
public final class NativeOwnershipRegressionTest {
    public static void main(String[] args) throws Exception {
        var owned = new ArrayList<AbstractImmutableNativeReference>();
        try (var rock = mockStatic(RocksDB.class);
             var cf = mockConstruction(ColumnFamilyOptions.class,withSettings().defaultAnswer(RETURNS_SELF),(value,context)->owned.add(value));
             var cache = mockConstruction(HyperClockCache.class,(value,context)->owned.add(value));
             var filter = mockConstruction(BloomFilter.class,(value,context)->owned.add(value));
             var options = mockConstruction(DBOptions.class,withSettings().defaultAnswer(RETURNS_SELF),(value,context)->owned.add(value))) {
            rock.when(()->RocksDB.open(any(DBOptions.class),anyString(),anyList(),anyList()))
                    .thenThrow(new RocksDBException("expected open failure"));
            try { new RocksDBStorageBackend("unused"); throw new AssertionError("open failure swallowed"); }
            catch (RuntimeException expected) {
                if (!(expected.getCause() instanceof RocksDBException)) throw expected;
            }
            if (owned.size()!=5) throw new AssertionError("allocation boundary changed: "+owned.size());
            for (var resource:owned) verify(resource).close();
            System.out.println("PASS: failed RocksDB open closes every allocated native option, filter and cache");
        }
    }
}
