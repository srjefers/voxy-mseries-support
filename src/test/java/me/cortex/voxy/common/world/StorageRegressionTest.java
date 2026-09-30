package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.service.SectionSavingService;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StorageRegressionTest {
    private static int failures;
    private static void check(String name, ThrowingRunnable test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; error.printStackTrace(); System.err.println("FAIL: " + name); }
    }
    private interface ThrowingRunnable { void run() throws Exception; }
    private static void expect(boolean result, String message) { if (!result) throw new AssertionError(message); }

    private static void connectSaver(WorldEngine engine, SectionSavingService saver) {
        engine.setSaveCallback((WorldEngine.ISectionSaveCallback) Proxy.newProxyInstance(
                StorageRegressionTest.class.getClassLoader(), new Class[]{WorldEngine.ISectionSaveCallback.class},
                (proxy, method, args) -> {
                    for (var enqueue : SectionSavingService.class.getMethods()) {
                        if (enqueue.getName().equals("enqueueSave") && enqueue.getParameterCount() == args.length) {
                            return enqueue.invoke(saver, args);
                        }
                    }
                    throw new AssertionError("Saving callback and service API disagree");
                }));
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check("dirty changes survive while a save is already queued", () -> {
            var storage = new SectionSerializationStorage(new MemoryStorageBackend(1));
            var engine = new WorldEngine(storage);
            var section = engine.acquire(0, 1, 2, 3);
            section.exchangeIsInSaveQueue(true);
            engine.markDirty(section);
            expect(section.isDirty, "queued save suppressed a concurrent modification");
            section.exchangeIsInSaveQueue(false); section.setNotDirty(); section.release(); engine.free();
        });
        check("scheduling a save keeps dirty ownership until worker starts", () -> {
            var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend(1)));
            var manager = new ServiceManager(count -> {});
            var saver = new SectionSavingService(manager);
            connectSaver(engine, saver);
            var section = engine.acquire(0, 1, 2, 3);
            engine.markDirty(section); engine.saveSection(section);
            expect(section.isDirty && section.inSaveQueue, "save submission cleared dirty state early");
            expect(section.getRefCount() == 2, "queue does not own exactly one reference");
            manager.tryRunAJob();
            expect(!section.isDirty && !section.inSaveQueue, "completed save retained dirty/queue state");
            section.release(); saver.shutdown(); manager.shutdown(); engine.free();
        });
        check("a refused save retains dirty data without recursive unload", () -> {
            var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend(1)));
            engine.setSaveCallback((world, section, nonBlocking, alreadyAcquired) -> false);
            var section = engine.acquire(0, 6, 7, 8);
            engine.markDirty(section);
            section.release();
            var retained = engine.acquire(0, 6, 7, 8);
            expect(retained == section && retained.isDirty, "refused save discarded dirty data");
            retained.setNotDirty(); retained.release(); engine.free();
        });
        check("write during save is persisted again on unload", () -> {
            var started = new CountDownLatch(1); var continueSave = new CountDownLatch(1);
            var saves = new java.util.concurrent.atomic.AtomicInteger();
            var storage = new SectionSerializationStorage(new MemoryStorageBackend(1)) {
                @Override public void saveSection(WorldSection section) {
                    super.saveSection(section);
                    if (saves.incrementAndGet() == 1) {
                        started.countDown();
                        try { if (!continueSave.await(5, TimeUnit.SECONDS)) throw new AssertionError("writer did not resume saver"); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                    }
                }
            };
            var engine = new WorldEngine(storage); var manager = new ServiceManager(count -> {});
            var saver = new SectionSavingService(manager); connectSaver(engine, saver);
            var section = engine.acquire(0, 4, 5, 6);
            section.data[0] = 1L << 27; engine.markDirty(section); engine.saveSection(section);
            var failure = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> { try { manager.tryRunAJob(); } catch (Throwable error) { failure.set(error); } });
            worker.start();
            try {
                expect(started.await(5, TimeUnit.SECONDS), "save never started");
                section.data[0] = 2L << 27; engine.markDirty(section); section.release();
            } finally { continueSave.countDown(); worker.join(5000); }
            expect(!worker.isAlive() && failure.get() == null, "worker failed or blocked: " + failure.get());
            while (saver.getTaskCount() > 0) manager.tryRunAJob();
            expect(saves.get() == 2, "unload lost or duplicated the dirty save");
            var reloaded = WorldSection._createRawUntrackedUnsafeSection(0, 4, 5, 6);
            expect(storage.loadSection(reloaded) == 0 && reloaded.data[0] == 2L << 27, "latest data not persisted");
            saver.shutdown(); manager.shutdown(); engine.free();
        });
        check("serialization remains byte-compatible with preceding format", () -> {
            var section = WorldSection._createRawUntrackedUnsafeSection(0, -3, 7, 22);
            for (int i = 0; i < section.data.length; i++) section.data[i] = ((long)(i / 17 % 11) << 27) | ((long)(i % 16) << 56);
            section.nonEmptyChildren = (byte) 0xa5;
            var serialized = SaveLoadSystem3.serialize(section);
            byte[] bytes = new byte[(int)serialized.size]; serialized.asByteBuffer().get(bytes);
            Path fixture = Path.of(System.getProperty("voxy.storageFixture", "/private/tmp/voxy-storage-v0.bin"));
            if (Files.exists(fixture)) expect(Arrays.equals(bytes, Files.readAllBytes(fixture)), "stored format changed");
            else Files.write(fixture, bytes);
            var reloaded = WorldSection._createRawUntrackedUnsafeSection(0, -3, 7, 22);
            expect(SaveLoadSystem3.deserialize(reloaded, serialized), "cannot deserialize existing format");
            expect(Arrays.equals(reloaded.data, section.data) && reloaded.nonEmptyChildren == (byte)0xa5, "round-trip data mismatch");
            var freeable = MemoryBuffer.class.getDeclaredField("freeable"); freeable.setAccessible(true);
            if (freeable.getBoolean(serialized)) serialized.free();
        });
        check("ARM64 RocksDB reopens existing data and mappings", () -> {
            Path fixture = Path.of(System.getProperty("voxy.databaseFixture", "/private/tmp/voxy-rocksdb-10.2-compat"));
            Files.createDirectories(fixture);
            byte[] expected = "existing-voxy-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long key = WorldEngine.getWorldSectionId(2, -8, 10, 20);
            var backend = new RocksDBStorageBackend(fixture.toString());
            var scratch = new MemoryBuffer(1024);
            var existing = backend.getSectionData(key, scratch.createUntrackedUnfreeableReference());
            if (existing == null) {
                scratch.asByteBuffer().put(expected);
                backend.setSectionData(key, scratch.createUntrackedUnfreeableReference().subSize(expected.length));
                backend.putIdMapping(42, ByteBuffer.wrap(new byte[]{7, 8, 9}));
            } else {
                byte[] found = new byte[(int)existing.size]; existing.asByteBuffer().get(found);
                expect(Arrays.equals(found, expected), "pre-upgrade database data changed");
            }
            backend.close();
            backend = new RocksDBStorageBackend(fixture.toString());
            existing = backend.getSectionData(key, scratch.createUntrackedUnfreeableReference());
            byte[] found = new byte[(int)existing.size]; existing.asByteBuffer().get(found);
            expect(Arrays.equals(found, expected), "database reopen changed bytes");
            for (int i = 0; i < 100; i++) expect(Arrays.equals(backend.getIdMappingsData().get(42), new byte[]{7, 8, 9}), "mapping changed");
            backend.close(); scratch.free();
        });
        if (failures != 0) throw new AssertionError(failures + " storage regressions failed");
    }
}
