package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.GeometryCache;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

import static org.lwjgl.opengl.GL43C.*;

//TODO: create an "async upload stream", that is, the upload stream is a raw mapped buffer pointer that can be written to
// which is then synced to the gpu on "render thread sync",


//An "async host" for a NodeManager, has specific synchonius entry and exit points
// this is done off thread to reduce the amount of work done on the render thread, improving frame stability and reducing runtime overhead
public class AsyncNodeManager {
    private static final VarHandle RESULT_HANDLE;
    private static final VarHandle RESULT_CACHE_1_HANDLE;
    private static final VarHandle RESULT_CACHE_2_HANDLE;
    static {
        try {
            RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", GeometryPublication.class);
            RESULT_CACHE_1_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache1", GeometryPublication.class);
            RESULT_CACHE_2_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache2", GeometryPublication.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Thread thread;
    public final int maxNodeCount;
    private final long geometryCapacity;
    private volatile boolean running = true;
    private volatile Throwable uncaughtException;
    private boolean stopped;

    private final NodeManager manager;
    private final WorldEngine probeWorld;
    private final BasicAsyncGeometryManager geometryManager;
    private final IGeometryData geometryData;
    private final SectionUpdateRouter router;

    private final GeometryCache geometryCache = new GeometryCache(1L<<32);

    private final AtomicInteger workCounter = new AtomicInteger();

    @SuppressWarnings("FieldMayBeFinal")
    private volatile GeometryPublication results = null, resultCache1 = new GeometryPublication(), resultCache2 = new GeometryPublication();


    // Worker-local until handed to RESULT_HANDLE; stop reads it only after joining.
    private GeometryPublication assembling;

    //locals for during iteration
    private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();//"Encoded" add/remove id, first bit indicates if its add or remove, 1 is add
    //Top bit indicates clear or reset
    private final IntOpenHashSet cleanerIdResetClear = new IntOpenHashSet();//Tells the cleaner if it needs to clear the id to 0, or reset the id to the current frame

    private boolean needsWaitForSync = false;

    public AsyncNodeManager(int maxNodeCount, IGeometryData geometryData, RenderGenerationService renderService) {
        //Note the current implmentation of ISectionWatcher is threadsafe
        //Note: geometry data is the data store/source, not the management, it is just a raw store of data
        // it MUST ONLY be accessed on the render thread
        // AsyncNodeManager will use an AsyncGeometryManager as the manager for the data store, and sync the results on the render thread
        this.probeWorld = renderService.getWorld();
        this.geometryData = geometryData;
        this.geometryCapacity = ((BasicSectionGeometryData)geometryData).getGeometryCapacityBytes();

        this.maxNodeCount = maxNodeCount;

        this.thread = new Thread(()->{
            try {
                while (this.running) {
                    this.run();
                }
            } catch (Exception e) {
                Logger.error("Critical error occurred in async processor", e);
                throw e;
            }
        });
        this.thread.setUncaughtExceptionHandler((thread, failure) -> {
            this.recordFailure(failure);
        });
        this.thread.setName("Async Node Manager");
        this.thread.setDaemon(true);// don't block JVM shutdown if this thread is stuck

        this.geometryManager = new BasicAsyncGeometryManager(((BasicSectionGeometryData)geometryData).getMaxSectionCount(), this.geometryCapacity);

        this.router = new SectionUpdateRouter();
        this.router.setCallbacks(pos->{//On initial render gen, try get from geometry cache
            var cachedGeometry = this.geometryCache.remove(pos);
            if (cachedGeometry != null) {//Use the cached geometry
                this.submitGeometryResult(cachedGeometry);
            } else {//Else we need to request it
                renderService.enqueueTask(pos);
            }
        }, renderService::enqueueTask, this::submitChildChange);
        renderService.setResultConsumer(this::submitGeometryResult);

        this.manager = new NodeManager(maxNodeCount, this.geometryManager, this.router);

        //Dont do the move... is just to much effort
        this.manager.setClear(new NodeManager.ICleaner() {
            @Override
            public void alloc(int id) {
                AsyncNodeManager.this.cleanerIdResetClear.remove(id);//Remove clear
                AsyncNodeManager.this.cleanerIdResetClear.add(id|(1<<31));//Add reset
            }

            @Override
            public void move(int from, int to) {
                //noop (sorry :( will cause some perf loss/incorrect cleaning )
            }

            @Override
            public void free(int id) {
                AsyncNodeManager.this.cleanerIdResetClear.remove(id|(1<<31));//Remove reset
                AsyncNodeManager.this.cleanerIdResetClear.add(id);//Add clear
            }
        });
        this.manager.setTLNCallbacks(id->{
            if (!this.tlnIdChange.remove(id)) {
                if (!this.tlnIdChange.add(id|(1<<31))) {
                    throw new IllegalStateException();
                }
            }
        }, id -> {
            if (!this.tlnIdChange.remove(id|(1<<31))) {
                if (!this.tlnIdChange.add(id)) {
                    throw new IllegalStateException();
                }
            }
        });
    }

    private GeometryPublication getMakeResultObject() {
        GeometryPublication resultSet = (GeometryPublication)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
        if (resultSet == null) {//Not in the first object
            resultSet = (GeometryPublication)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
        }
        if (resultSet == null) {
            throw new IllegalStateException("There should always be an object in the result set cache pair");
        }
        //Reset everything to default
        this.assembling = resultSet;
        resultSet.reset();
        return resultSet;
    }

    /** UBO binding for scatter.comp's `Push { uint count; }` block. */
    private static final int SCATTER_PUSH_BINDING = GeometryPublication.SCATTER_PUSH_BINDING;

    private final me.cortex.voxy.client.core.gpu.RenderBackend backend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get();

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline scatterWrite = this.backend.createComputePipeline(
            new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                    me.cortex.voxy.client.core.gl.shader.ShaderLoader.parse("voxy:util/scatter.comp"),
                    java.util.Map.of(
                            "INPUT_BUFFER_BINDING", "0",
                            "OUTPUT_BUFFER1_BINDING", "1",
                            "OUTPUT_BUFFER2_BINDING", "2",
                            "PUSH_BINDING", Integer.toString(SCATTER_PUSH_BINDING)),
                    null, null,
                    128, 1, 1,
                    "AsyncNodeManager.scatterWrite"));

    private final me.cortex.voxy.client.core.gpu.IGpuPipeline multiMemcpy = this.backend.createComputePipeline(
            new me.cortex.voxy.client.core.gpu.ComputePipelineDesc(
                    me.cortex.voxy.client.core.gl.shader.ShaderLoader.parse("voxy:util/memcpy.comp"),
                    java.util.Map.of(
                            "INPUT_HEADER_BUFFER_BINDING", "0",
                            "INPUT_DATA_BUFFER_BINDING", "1",
                            "OUTPUT_BUFFER_BINDING", "2"),
                    null, null,
                    256, 1, 1,
                    "AsyncNodeManager.multiMemcpy"));

    private void run() {
        if (this.workCounter.get() <= 0) {
            //TODO: here, instead of parking, we can do more work on other sub-tasks such as filtering the mesh build queue
            LockSupport.park();
            if (this.workCounter.get() <= 0 || !this.running) {//No work
                return;
            }
            //This is a funny thing, wait a bit, this allows for better batching, but this thread is independent of everything else so waiting a bit should be mostly ok
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (!this.running) {
            return;
        }


        int workDone = 0;

        {
            LongOpenHashSet add = null;
            LongOpenHashSet rem = null;
            long stamp = this.tlnLock.writeLock();

            if (!this.tlnAdd.isEmpty()) {
                add = new LongOpenHashSet(this.tlnAdd);
                this.tlnAdd.clear();
            }
            if (!this.tlnRem.isEmpty()) {
                rem = new LongOpenHashSet(this.tlnRem);
                this.tlnRem.clear();
            }

            this.tlnLock.unlockWrite(stamp);
            int work = 0;
            if (rem != null) {
                var iter = rem.longIterator();
                while (iter.hasNext()) {
                    this.manager.removeTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            if (add != null) {
                var iter = add.longIterator();
                while (iter.hasNext()) {
                    this.manager.insertTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            workDone += work;
        }

        do {
            var job = this.childUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processChildChange(job.key, job.getNonEmptyChildren());
            job.release();
        } while (true);


        //Limit uploading as well as by geometry capacity being available
        // must have 50 mb of free geometry space to upload
        long estimatedUploadBytes = 0;
        for (int limit = 0; limit < 300 && estimatedUploadBytes < (1_000L << 10)
                && this.geometryCapacity - this.geometryManager.getGeometryUsedBytes() > 50_000_000L; limit++) {
            var job = this.geometryUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            long uploadBytes = job.geometryBuffer == null ? 0 : job.geometryBuffer.size;
            this.manager.processGeometryResult(job);
            estimatedUploadBytes += uploadBytes;
        }

        while (true) {//Process all request batches
            var job = this.requestBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr);
            ptr += 8;//Its 8 to keep alignment
            if (job.size < count * 8L + 8) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.processRequest(pos);
            }
            job.free();
        }


        do {
            var job = this.removeBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int zeroCount = 0;
            for (int i = 0; i < NodeCleaner.OUTPUT_COUNT; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;

                if (pos == -1) {
                    //TODO: investigate how or what this happens
                    continue;
                }

                if (pos == 0 && zeroCount++>0) {
                    Logger.error("Remove node pos is 0 " + zeroCount + " times, this is really bad, please report" );
                    continue;
                }

                this.manager.removeNodeGeometry(pos);
            }
            job.free();
        } while (true);

        if (this.workCounter.addAndGet(-workDone) < 0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //Due to synchronization "issues", wait a millis (give up this time slice)
            if (this.workCounter.get() < 0) {
                Logger.error("Work counter less than zero, hope it fixes itself...");
                //return;
            }
        }

        if (workDone == 0) {//Nothing happened, which is odd, but just return
            //Should probably log that nothing happened, at least once
            return;
        }
        //=====================
        //process output events and atomically sync to results

        //Events into manager
        //manager.insertTopLevelNode();
        //manager.removeTopLevelNode();

        //manager.removeNodeGeometry();

        //manager.processRequest();
        //manager.processChildChange();
        //manager.processGeometryResult();


        //Outputs from manager
        //manager.setClear();
        //manager.setTLNCallbacks();

        //manager.writeChanges()


        //Run in a loop, process all the input events, collect the output events merge with previous and publish
        // note: inner event processing is a loop, is.. should be synced to attomic/volatile variable that is being watched
        // when frametime comes around, want to exit out as quick as possible, or make the event publishing
        // "effectivly immediately", that is, atomicly swap out the render side event updates

        //like
        // var current = <new events>
        // var old = getAndSet(this.events, null);
        // if (old != null) {current = merge(old, current);}
        // getAndSet(this.events, current);
        // if (old == null) {cleanAllEventsUpToThisPoint();}//(i.e. clear any buffers or maps containing data revolving around uncommited render thread data events)

        // this creates a lock free event update loop, allowing the render thread to never stall on waiting

        //TODO: NOTE: THIS MUST BE A SINGLE OBJECT THAT IS EXCHANGED
        // for it to be effectivly synchonized all outgoing events/effects _MUST_ happen at the same time
        // for this to be lock free an entire object containing ALL the events that must be synced must be exchanged


        //TODO: also note! this can be done for the processing of rendered out block models!!
        // (it might be able to also be put in this thread, maybe? but is proabably worth putting in own thread for latency reasons)
        if (this.needsWaitForSync) {
            while (RESULT_HANDLE.get(this) != null && this.running) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }


        var prev = (GeometryPublication) RESULT_HANDLE.getAndSet(this, null);
        this.assembling = prev;
        GeometryPublication results = null;
        if (prev == null) {
            this.needsWaitForSync = false;
            results = this.getMakeResultObject();
            //Clear old data (if it exists), create a new result set
            results.tlnDelta.addAll(this.tlnIdChange);
            this.tlnIdChange.clear();

            if (!this.geometryManager.getUploads().isEmpty()){//Put in new data into sync set
                var iter = this.geometryManager.getUploads().int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                this.geometryManager.getUploads().clear();
            }

            this.geometryManager.getHeapRemovals().clear();//We dont do removals on new data (as there is "none")
            results.cleanerOperations.addAll(this.cleanerIdResetClear); this.cleanerIdResetClear.clear();
        } else {
            results = prev;
            // merge with the previous result set

            if (!this.tlnIdChange.isEmpty()) {//Merge top level node id changes
                var iter = this.tlnIdChange.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    if (!results.tlnDelta.remove(val ^ (1 << 31))) {//Remove opposite
                        results.tlnDelta.add(val);//Add this if not added
                    }
                }
                this.tlnIdChange.clear();
            }

            if (!this.cleanerIdResetClear.isEmpty()) {//Merge top level node id changes
                var iter = this.cleanerIdResetClear.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    results.cleanerOperations.remove(val^(1<<31));//Remove opposite
                    results.cleanerOperations.add(val);//Add this
                }
                this.cleanerIdResetClear.clear();
            }

            if (!this.geometryManager.getHeapRemovals().isEmpty()) {//Remove and free all the removed geometry uploads
                var rem = this.geometryManager.getHeapRemovals();
                var iter = rem.intIterator();
                while (iter.hasNext()) {
                    results.geometryUpload.remove(iter.nextInt());
                }
                rem.clear();
            }

            if (!this.geometryManager.getUploads().isEmpty()) {//Add all the new uploads to the result set
                var add = this.geometryManager.getUploads();
                var iter = add.int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                add.clear();
            }
        }

        {//This is the same regardless of if is a merge or new result
            //Geometry id metadata updates
            if (!this.geometryManager.getUpdateIds().isEmpty()) {
                var ids = this.geometryManager.getUpdateIds();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int scatterAddr = (val<<1)|(1<<31);//Since we write to the second buffer

                    //Geometry buffer is index of 1, so mutate to put it in that location, it is also 32 bytes, so needs to be split into 2 separate scatter writes
                    long ptrA = results.getScatterWritePtr(scatterAddr+0, 1);
                    long ptrB = results.getScatterWritePtr(scatterAddr+1, 0);

                    //Write update data
                    this.geometryManager.writeMetadataSplit(val, ptrA, ptrB);
                    if (me.cortex.voxy.client.core.SectionProbe.enabled(this.probeWorld)) {
                        long position=((long)MemoryUtil.memGetInt(ptrA)<<32)|Integer.toUnsignedLong(MemoryUtil.memGetInt(ptrA+4));
                        me.cortex.voxy.client.core.SectionProbe.record(this.probeWorld,position,"metadata",Integer.toString(val));
                    }
                }
                ids.clear();
            }

            //Node updates
            if (!this.manager.getNodeUpdates().isEmpty()) {
                var ids = this.manager.getNodeUpdates();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    //Dont need to modify the write location since we write to buffer 0
                    long ptr = results.getScatterWritePtr(val);
                    //Write updated data
                    this.manager.writeNode(val, ptr);
                }
                ids.clear();
            }
        }

        results.geometrySectionCount = this.geometryManager.getSectionCount();
        results.usedGeometry = this.geometryManager.getGeometryUsedBytes();
        results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();

        this.needsWaitForSync |= results.geometryUpload.currentElemCopyAmount*8L > 2L<<20;//2mb limit per frame
        this.needsWaitForSync |= results.cleanerOperations.size() > 1024;
        this.needsWaitForSync |= results.scatterWriteLocationMap.size() > 4096;
        this.needsWaitForSync |= results.tlnDelta.size() > 10;

        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }
        this.assembling = null;
    }

    private IntConsumer tlnAddCallback; private IntConsumer tlnRemoveCallback;
    //Render thread synchronization
    public void tick(IGpuBuffer nodeBuffer, NodeCleaner cleaner) {//TODO: dont pass nodeBuffer here??, do something else thats better
        this.checkWorkerFailure();
        var results = (GeometryPublication)RESULT_HANDLE.getAndSet(this, null);//Acquire the results
        if (results == null) {//There are no new results to process, return
            return;
        }
        DIAG_TICK_WITH_RESULTS_COUNT.incrementAndGet();
        DIAG_LAST_TICK_SECTION_COUNT.set(results.geometrySectionCount);
        if (!results.geometryUpload.dataUploadPoints.isEmpty()) {
            DIAG_TICK_WITH_UPLOADS_COUNT.incrementAndGet();
        }

        try {
            results.publish(this.backend, this.multiMemcpy, this.scatterWrite,
                    (BasicSectionGeometryData)this.geometryData, nodeBuffer, cleaner,
                    this.tlnAddCallback, this.tlnRemoveCallback);
            this.currentMaxNodeId = results.currentMaxNodeId;
            this.usedGeometryAmount = results.usedGeometry;
            if (!RESULT_CACHE_1_HANDLE.compareAndSet(this, null, results)
                    && !RESULT_CACHE_2_HANDLE.compareAndSet(this, null, results)) {
                throw new IllegalStateException("Could not insert result into cache");
            }
        } catch (RuntimeException | Error failure) {
            this.recordFailure(failure);
            try { results.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void setTLNAddRemoveCallbacks(IntConsumer add, IntConsumer remove) {
        this.tlnAddCallback = add;
        this.tlnRemoveCallback = remove;
    }

    private int currentMaxNodeId = 0;
    public int getCurrentMaxNodeId() {
        return this.currentMaxNodeId;
    }

    private long usedGeometryAmount = 0;
    public long getUsedGeometryCapacity() {
        return this.usedGeometryAmount;
    }

    public long getGeometryCapacity() {
        return this.geometryCapacity;
    }


    //==================================================================================================================
    //Incoming events

    //TODO: add atomic counters for each event type probably
    private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<WorldSection> childUpdateQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<BuiltSection> geometryUpdateQueue = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<MemoryBuffer> removeBatchQueue = new ConcurrentLinkedDeque<>();

    private final StampedLock tlnLock = new StampedLock();
    private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
    private final LongOpenHashSet tlnRem = new LongOpenHashSet();

    private synchronized void recordFailure(Throwable failure) {
        if (this.uncaughtException == null) this.uncaughtException = failure;
        this.running = false;
        LockSupport.unpark(this.thread);
    }

    private void checkWorkerFailure() {
        if (this.uncaughtException != null) throw new RuntimeException("Async node processing or publication failed", this.uncaughtException);
    }

    private void addWork() {
        this.checkWorkerFailure();
        if (!this.running) throw new IllegalStateException("Not running");
        if (this.workCounter.getAndIncrement() == 0) {
            LockSupport.unpark(this.thread);
        }
    }

    public void submitRequestBatch(MemoryBuffer batch) {//Only called from render thread
        this.requestBatchQueue.add(batch);
        this.addWork();
    }

    private void submitChildChange(WorldSection section) {
        if (!this.running) {
            return;
        }
        section.acquire();//We must acquire the section before putting in the queue
        this.childUpdateQueue.add(section);
        this.addWork();
    }

    /** M13 diagnostic counters — read by AbstractRenderPipeline's Metal-DIAG dump. */
    public static final java.util.concurrent.atomic.AtomicLong DIAG_WORLD_EVENT_COUNT = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DIAG_GEOMETRY_RESULT_COUNT = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DIAG_TOP_LEVEL_ADD_COUNT = new java.util.concurrent.atomic.AtomicLong();
    /** Times AsyncNodeManager.tick() ran and consumed a non-null GeometryPublication. */
    public static final java.util.concurrent.atomic.AtomicLong DIAG_TICK_WITH_RESULTS_COUNT = new java.util.concurrent.atomic.AtomicLong();
    /** Highest geometrySectionCount observed by AsyncNodeManager.tick(). */
    public static final java.util.concurrent.atomic.AtomicLong DIAG_LAST_TICK_SECTION_COUNT = new java.util.concurrent.atomic.AtomicLong();
    /** Times AsyncNodeManager.tick() saw non-empty geometry uploads in the sync results. */
    public static final java.util.concurrent.atomic.AtomicLong DIAG_TICK_WITH_UPLOADS_COUNT = new java.util.concurrent.atomic.AtomicLong();

    private void submitGeometryResult(BuiltSection geometry) {
        DIAG_GEOMETRY_RESULT_COUNT.incrementAndGet();
        if (!this.running) {
            geometry.free();
            return;
        }
        this.geometryUpdateQueue.add(geometry);
        this.addWork();
    }

    public void submitRemoveBatch(MemoryBuffer batch) {//Only called from render thread
        this.removeBatchQueue.add(batch);
        this.addWork();
    }

    public void addTopLevel(long section) {//Only called from render thread
        DIAG_TOP_LEVEL_ADD_COUNT.incrementAndGet();
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnRem.remove(section)) {
            state += this.tlnAdd.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    public void removeTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnAdd.remove(section)) {
            state += this.tlnRem.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    //==================================================================================================================

    public void start() {
        this.thread.start();
    }

    public void quiesce() {
        this.running = false;
        LockSupport.unpark(this.thread);
        me.cortex.voxy.common.util.ResourceCleanup.join(this.thread);
    }

    public void stop() {
        if (this.stopped) {
            throw new IllegalStateException();
        }
        this.stopped = true;
        this.quiesce();

        var releases = new java.util.ArrayList<Runnable>();
        MemoryBuffer buffer;
        while ((buffer = this.requestBatchQueue.poll()) != null) releases.add(buffer::free);
        while ((buffer = this.removeBatchQueue.poll()) != null) releases.add(buffer::free);
        BuiltSection mesh;
        while ((mesh = this.geometryUpdateQueue.poll()) != null) releases.add(mesh::free);
        WorldSection section;
        while ((section = this.childUpdateQueue.poll()) != null) releases.add(section::release);
        for (var slot : new VarHandle[]{RESULT_HANDLE, RESULT_CACHE_1_HANDLE, RESULT_CACHE_2_HANDLE}) {
            var result = (GeometryPublication)slot.getAndSet(this, null);
            if (result != null) releases.add(result::close);
        }
        if (this.assembling != null) { releases.add(this.assembling::close); this.assembling = null; }
        releases.add(this.scatterWrite::close);
        releases.add(this.multiMemcpy::close);
        releases.add(this.geometryCache::free);
        me.cortex.voxy.common.util.ResourceCleanup.run(releases.toArray(Runnable[]::new));
    }

    public void addDebug(List<String> debug) {
        debug.add("UC/GC: " + (this.getUsedGeometryCapacity()/(1<<20))+"/"+(this.getGeometryCapacity()/(1<<20)));
        //debug.add("GUQ/NRC: " + this.geometryUpdateQueue.size()+"/"+this.removeBatchQueue.size());
    }

    public boolean hasWork() {
        return this.workCounter.get()!=0 || RESULT_HANDLE.get(this) != null;
    }

    public WorldEngine getWorld() { return this.probeWorld; }

    public void worldEvent(WorldSection section, int flags, int neighborMask) {
        if (me.cortex.voxy.client.core.SectionProbe.enabled(this.probeWorld))
            me.cortex.voxy.client.core.SectionProbe.record(this.probeWorld,section.key,"ingest","changes="+flags+",children="+section.getNonEmptyChildren());
        DIAG_WORLD_EVENT_COUNT.incrementAndGet();
        //If there is any change, we need to clear the geometry cache before emitting update
        this.geometryCache.clear(section.key);

        this.router.forwardEvent(section, flags);

        if (neighborMask != 0) {//trigger rebuilds for neighbors
            if ((neighborMask&0b000001)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y-1, section.z));//-y
            if ((neighborMask&0b000010)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y+1, section.z));//+y
            if ((neighborMask&0b000100)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x-1, section.y, section.z));//-x
            if ((neighborMask&0b001000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x+1, section.y, section.z));//+x
            if ((neighborMask&0b010000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z-1));//-z
            if ((neighborMask&0b100000)!=0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z+1));//+z
        }
    }

}
