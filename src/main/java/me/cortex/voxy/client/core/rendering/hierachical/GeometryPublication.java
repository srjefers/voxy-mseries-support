package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

/** One owned mesh/node update: GPU recording precedes readiness, and scratch has one release path. */
final class GeometryPublication implements AutoCloseable {
    static final int SCATTER_PUSH_BINDING = 14;
    //Node id updates + size
    int currentMaxNodeId;// the id of the ending of the node ids

    //TLN add/rem
    final IntOpenHashSet tlnDelta = new IntOpenHashSet();

    //Deltas for geometry store
    int geometrySectionCount;
    long usedGeometry;
    final ComputeMemoryCopy geometryUpload = new ComputeMemoryCopy();

    //Scatter writes for both geometry and node metadata
    MemoryBuffer scatterWriteBuffer = new MemoryBuffer(8192*2);
    final Int2IntOpenHashMap scatterWriteLocationMap = new Int2IntOpenHashMap(1024);
    {this.scatterWriteLocationMap.defaultReturnValue(-1);}

    //Cleaner operations
    final IntOpenHashSet cleanerOperations = new IntOpenHashSet();

    public void reset() {
        this.cleanerOperations.clear();
        this.scatterWriteLocationMap.clear();
        this.currentMaxNodeId = 0;
        this.tlnDelta.clear();
        this.geometrySectionCount = 0;
        this.usedGeometry = 0;
        this.geometryUpload.reset();
    }

    //Get or create a scatter write address for the given location
    public long getScatterWritePtr(int location) {
        return this.getScatterWritePtr(location, 0);
    }

    //ensureExtra is used to ensure that allocations are "effectivly" in the same memory block (kinda?)
    public long getScatterWritePtr(int location, int ensureExtra) {
        int loc = this.scatterWriteLocationMap.get(location);
        if (loc == -1) {//Location doesnt exist, create it
            this.ensureScatterBufferCapacity(1+ensureExtra);//Ensure can contain capacity for this + extra
            int baseId = this.scatterWriteLocationMap.size();
            int chunkBase = (baseId/4)*5;//Base uvec4 index
            int innerId   = baseId&3;
            MemoryUtil.memPutInt(this.scatterWriteBuffer.address + (chunkBase*16L) + (innerId*4L), location);//Set the write location
            int writeLocation = (chunkBase+1+innerId);//Write location in uvec4
            this.scatterWriteLocationMap.put(location, writeLocation);
            return this.scatterWriteBuffer.address + (writeLocation*16L);
        } else {
            return this.scatterWriteBuffer.address + (16L*loc);
        }
    }

    void ensureScatterBufferCapacity(int extra) {
        int requiredChunks = ((this.scatterWriteLocationMap.size()+extra)+3)/4;//4 entries in a chunk
        long requiredSize = requiredChunks*5L*16L;//5 uvec4 per chunk, 16 bytes per uvec4
        if (this.scatterWriteBuffer.size <= requiredSize) {//Needs resize
            long newSize = (long) ((this.scatterWriteBuffer.size*1.5) + extra*80L);
            newSize = ((newSize+79)/80)*80;//Ceil to chunk size

            Logger.info("Expanding scatter update buffer to " + newSize);

            var newBuffer = new MemoryBuffer(newSize);
            this.scatterWriteBuffer.cpyTo(newBuffer.address);
            this.scatterWriteBuffer.free();
            this.scatterWriteBuffer = newBuffer;
        }
    }
    void publish(me.cortex.voxy.client.core.gpu.RenderBackend backend,
                 me.cortex.voxy.client.core.gpu.IGpuPipeline multiMemcpy,
                 me.cortex.voxy.client.core.gpu.IGpuPipeline scatterWrite,
                 BasicSectionGeometryData store, IGpuBuffer nodeBuffer, NodeCleaner cleaner,
                 it.unimi.dsi.fastutil.ints.IntConsumer add, it.unimi.dsi.fastutil.ints.IntConsumer remove) {
        {//Update basic geometry data
            var upload = this.geometryUpload;
            if (!upload.dataUploadPoints.isEmpty()) {
                store.ensureAccessable(upload.maxElementAccess);
                TimingStatistics.A.start();
                try {

                    int copies = upload.dataUploadPoints.size();
                    int scratchSize = (int) upload.arena.getSize() * 8;
                    long ptr = UploadStream.INSTANCE.rawUploadAddress(scratchSize + copies * 16);
                    UnsafeUtil.memcpy(upload.scratchHeaderBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
                    UnsafeUtil.memcpy(upload.scratchDataBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr + copies * 16L, scratchSize);
                    UploadStream.INSTANCE.commit();//Commit the buffer

                    if (copies > 500) {
                        Logger.warn("Large amount of copies, lag will probably happen: " + copies);
                    }

                    try (var encoder = backend.beginComputePass()) {
                        encoder.setPipeline(multiMemcpy);
                        // M12: UploadStream's persistent buffer flows through the
                        // encoder's IGpuPersistentBuffer overload now (was a raw
                        // glBindBufferRange against UploadStream.getRawBufferId()
                        // that broke on Metal because the buffer id isn't a GL name).
                        encoder.setBuffer(0, UploadStream.INSTANCE.getUploadBuffer(), ptr, copies * 16L);
                        encoder.setBuffer(1, UploadStream.INSTANCE.getUploadBuffer(), ptr + copies * 16L, scratchSize);
                        encoder.setBuffer(2, store.getGeometryBuffer(), 0);

                        encoder.barrier(me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER, me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER);
                        encoder.dispatch(copies, 1, 1);
                        encoder.barrier(me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER, me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER);
                    }

                } finally { TimingStatistics.A.stop(); }
            }
        }

        TimingStatistics.B.start();
        try {
            if (!scatterWriteLocationMap.isEmpty()) {//Scatter write
                int count = scatterWriteLocationMap.size();//Number of writes, not chunks or uvec4 count
                int chunks = (count+3)/4;
                int streamSize = chunks*80;//80 bytes per chunk, it is guaranteed the buffer is big enough
                long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize + 16);//Ensure it is 16 byte aligned
                ptr = (ptr+15L)&~0xFL;//Align up to 16 bytes
                MemoryUtil.memCopy(scatterWriteBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
                UploadStream.INSTANCE.commit();//Commit the buffer

                try (var encoder = backend.beginComputePass();
                     var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    encoder.setPipeline(scatterWrite);
                    // M12: cross-backend persistent-buffer bind — see multiMemcpy above.
                    encoder.setBuffer(0, UploadStream.INSTANCE.getUploadBuffer(), ptr, streamSize);
                    encoder.setBuffer(1, nodeBuffer, 0);
                    encoder.setBuffer(2, store.getMetadataBuffer(), 0);

                    long pushAddr = stack.nmalloc(4);
                    MemoryUtil.memPutInt(pushAddr, count);
                    encoder.setBytes(SCATTER_PUSH_BINDING, pushAddr, 4);

                    encoder.barrier(
                            me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER,
                            me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER);
                    encoder.dispatch((count + 127) / 128, 1, 1);
                    encoder.barrier(
                            me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER,
                            me.cortex.voxy.client.core.gpu.ComputeEncoder.BARRIER_SHADER);
                }
            }
        } finally { TimingStatistics.B.stop(); }

        TimingStatistics.C.start();
        try {
            if (!this.cleanerOperations.isEmpty()) {
                cleaner.updateIds(this.cleanerOperations);
            }
        } finally { TimingStatistics.C.stop(); }

        store.setSectionCount(this.geometrySectionCount);
        //top level node add/remove
        if (!this.tlnDelta.isEmpty()) {
            var iter = this.tlnDelta.intIterator();
            while (iter.hasNext()) {
                int val = iter.nextInt();
                if ((val&(1<<31))!=0) {//Add node
                    add.accept(val&(-1>>>1));
                } else {
                    remove.accept(val);
                }
            }
            //Dont need to clear as is not used again
        }

    }

    @Override public void close() {
        var scatter = this.scatterWriteBuffer;
        this.scatterWriteBuffer = null;
        me.cortex.voxy.common.util.ResourceCleanup.run(this.geometryUpload::free,
                () -> { if (scatter != null) scatter.free(); });
    }

    static class ComputeMemoryCopy {
        public int currentElemCopyAmount;
        public int maxElementAccess;
        MemoryBuffer scratchHeaderBuffer = new MemoryBuffer(1<<16);
        MemoryBuffer scratchDataBuffer = new MemoryBuffer(1<<20);

        final AllocationArena arena = new AllocationArena();
        final Int2IntOpenHashMap dataUploadPoints = new Int2IntOpenHashMap();//Points to the header index
        {this.dataUploadPoints.defaultReturnValue(-1);}


        public void remove(int point) {
            int header = this.dataUploadPoints.remove(point);
            if (header == -1) {//No upload for point
                return;
            }
            int size = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 8L);
            this.currentElemCopyAmount -= size;
            //Free the old memory addr from arena
            if (this.arena.free(MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L)) != size) {
                throw new IllegalStateException("Freed memory not same size as expected");
            }
            if (MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 4L) != point) {
                throw new IllegalStateException("Destination not the same as point");
            }

            //If we were the end upload header, return as we dont need to shuffle
            if (header == this.dataUploadPoints.size()) {
                long A = this.scratchHeaderBuffer.address + header*16L;
                //Zero the memory, for consistancy
                MemoryUtil.memPutLong(A, 0);
                MemoryUtil.memPutLong(A+8, 0);
                return;
            }

            //Else: we need to move the ending upload header from the end to where the freed point was
            int endingPoint = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L + 4);
            if (this.dataUploadPoints.get(endingPoint) != this.dataUploadPoints.size()) {
                throw new IllegalStateException("ending header not pointing at end point");
            }

            //Move the end header to the old header location
            long A = this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L;
            long B = this.scratchHeaderBuffer.address + header*16L;
            MemoryUtil.memPutLong(B, MemoryUtil.memGetLong(A)); MemoryUtil.memPutLong(A, 0);
            MemoryUtil.memPutLong(B+8, MemoryUtil.memGetLong(A+8)); MemoryUtil.memPutLong(A+8, 0);

            //Update the map
            this.dataUploadPoints.put(endingPoint, header);
        }

        public void upload(int point, MemoryBuffer data) {
            if ((data.size%8)!=0) throw new IllegalStateException("Data must be of size multiple 8");
            int elemSize = (int) (data.size / 8);
            this.maxElementAccess = Math.max(this.maxElementAccess, point + elemSize);
            int header = this.dataUploadPoints.get(point);
            if (header != -1) {
                //If we already have a header location, we just need to reallocate the data
                long headerPtr = this.scratchHeaderBuffer.address + header*16L;
                if (MemoryUtil.memGetInt(headerPtr+4L) != point) {
                    throw new IllegalStateException("Existing destination not the point");
                }
                int pSize = MemoryUtil.memGetInt(headerPtr+8L);//Previous size
                if (pSize == elemSize) {
                    //The data we are replacing is the same size, so just overwrite it, this is the easiest
                    data.cpyTo(this.scratchDataBuffer.address+MemoryUtil.memGetInt(headerPtr)*8L);
                } else {
                    //Dealloc
                    if (this.arena.free(MemoryUtil.memGetInt(headerPtr)) != pSize) {
                        throw new IllegalStateException("Freed allocation not size as expected");
                    }

                    this.currentElemCopyAmount -= pSize;
                    this.currentElemCopyAmount += elemSize;

                    int alloc = this.allocScratchDataPos(elemSize);//New allocation position
                    //Copy data into position
                    data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                    //Update the header
                    MemoryUtil.memPutInt(headerPtr, alloc);
                    MemoryUtil.memPutInt(headerPtr+8, elemSize);
                }
            } else {
                //We need to create and allocate a new header for the upload
                header = this.dataUploadPoints.size();
                this.dataUploadPoints.put(point, header);

                if (this.scratchHeaderBuffer.size<=header*16L) {
                    //We must resize the header buffer
                    long newSize = Math.max(this.scratchHeaderBuffer.size*2, header*16L);
                    Logger.info("Resizing scratch header buffer to: " + newSize);
                    var newScratch = new MemoryBuffer(newSize);
                    this.scratchHeaderBuffer.cpyTo(newScratch.address);
                    this.scratchHeaderBuffer.free();
                    this.scratchHeaderBuffer = newScratch;
                }

                long headerPtr = this.scratchHeaderBuffer.address + header*16L;//Header resize has happened so this is a stable address

                this.currentElemCopyAmount += elemSize;

                int alloc = this.allocScratchDataPos(elemSize);//New allocation position
                //Copy data into position
                data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                //Set header data
                MemoryUtil.memPutInt(headerPtr, alloc);
                MemoryUtil.memPutInt(headerPtr+4, point);
                MemoryUtil.memPutInt(headerPtr+8, elemSize);
            }
        }

        //This is done here as it enables easily doing scratch data resizing
        private int allocScratchDataPos(int size) {
            int pos = (int) this.arena.alloc(size);
            if (this.scratchDataBuffer.size <= (pos+size)*8L) {
                //We must resize :cri:
                long newSize = Math.max(this.scratchDataBuffer.size*2, (pos+size)*8L);
                Logger.info("Resizing scratch data buffer to: " + newSize);
                var newScratch = new MemoryBuffer(newSize);
                this.scratchDataBuffer.cpyTo(newScratch.address);
                this.scratchDataBuffer.free();
                this.scratchDataBuffer = newScratch;
            }
            return pos;
        }

        public void reset() {
            this.maxElementAccess = 0;
            this.currentElemCopyAmount = 0;
            this.dataUploadPoints.clear();
            this.arena.reset();
        }

        public void free() {
            var header = this.scratchHeaderBuffer; this.scratchHeaderBuffer = null;
            var data = this.scratchDataBuffer; this.scratchDataBuffer = null;
            me.cortex.voxy.common.util.ResourceCleanup.run(
                    () -> { if (header != null) header.free(); },
                    () -> { if (data != null) data.free(); });
        }
    }
}
