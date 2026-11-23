package me.cortex.voxy.client.core.rendering.section.geometry;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Consumer;


//Is basicly the manager for an "undefined" data store, the underlying store is irrelevant
// this manager serves as an overlay, that is, it allows an implementation to do "async management" of the data store
public class BasicAsyncGeometryManager implements IGeometryManager {
    private static final int ALLOC_ALIGN = parseAlign();
    private static int parseAlign() {
        int a = 128;
        String v = System.getenv("VOXY_GEOMETRY_ALLOC_ALIGN");
        if (v != null && !v.isBlank()) { try { a = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) {} }
        if (a < 1 || a > (1 << 20) || (a & (a - 1)) != 0) a = 128;//power of two, at most 1M elements (larger granules exceed the arena on the first allocation)
        me.cortex.voxy.common.Logger.info("[Voxy-SYNC] geometry allocation granule: " + a + " elements (upstream 72b3ade6; VOXY_GEOMETRY_ALLOC_ALIGN=1024 restores pre-sync)");
        return a;
    }
    public static final int SECTION_METADATA_SIZE = 32;

    private static final long GEOMETRY_ELEMENT_SIZE = 8;
    private final HierarchicalBitSet allocationSet;
    private final AllocationArena allocationHeap = new AllocationArena();
    private final ObjectArrayList<SectionMeta> sectionMetadata = new ObjectArrayList<>(1<<15);

    //Changes that need to be applied to the underlying data store to match this state
    private final IntOpenHashSet invalidatedIds = new IntOpenHashSet(1024);//Ids that need to be invalidated
    //TODO: maybe change from it pointing to MemoryBuffer, to BuiltSection
    //Note!: the int part is an unsigned int ptr, must be scaled by GEOMETRY_ELEMENT_SIZE
    private final Int2ObjectOpenHashMap<MemoryBuffer> heapUploads = new Int2ObjectOpenHashMap<>(1024);//Uploads into the buffer at the given location
    private final IntOpenHashSet heapRemoveUploads = new IntOpenHashSet(1024);//Any removals are added here, so that it can be properly synced
    private long usedCapacity = 0;

    public BasicAsyncGeometryManager(int maxSectionCount, long geometryCapacity) {
        this.allocationSet = new HierarchicalBitSet(maxSectionCount);
        if (geometryCapacity%GEOMETRY_ELEMENT_SIZE != 0)  throw new IllegalStateException();
        this.allocationHeap.setLimit(geometryCapacity/GEOMETRY_ELEMENT_SIZE);
    }

    @Override
    public int uploadSection(BuiltSection section) {
        return this.uploadReplaceSection(-1, section);
    }

    @Override
    public int uploadReplaceSection(int oldId, BuiltSection section) {
        if (section.isEmpty()) {
            throw new IllegalArgumentException("sectionData is empty, cannot upload nothing");
        }

        //Free the old id and replace it with a new one
        // if oldId is -1, then treat it as not previously existing

        //Free the old data if oldId is supplied
        if (oldId != -1) {
            //Its here just for future optimization potential
            this.removeSection(oldId);
        }

        int newId =  this.allocationSet.allocateNext();
        if (newId == HierarchicalBitSet.SET_FULL) {
            throw new IllegalStateException("Tried adding section when section count is already at capacity");
        }
        if (newId > this.sectionMetadata.size()) {
            throw new IllegalStateException("Size exceeds limits: " + newId + ", " + this.sectionMetadata.size() + ", " + this.allocationSet.getCount());
        }

        if (newId < this.sectionMetadata.size()) {
            if (this.sectionMetadata.get(newId) != null) {
                throw new IllegalStateException();
            }
        }

        var newMeta = this.createMeta(section);

        if (newId == this.sectionMetadata.size()) {
            this.sectionMetadata.add(newMeta);
        } else {
            if (this.sectionMetadata.set(newId, newMeta) != null) {
                throw new IllegalStateException();
            }
        }

        //Invalidate the section id
        this.invalidatedIds.add(newId);

        //HierarchicalOcclusionTraverser.HACKY_SECTION_COUNT = this.allocationSet.getCount();
        return newId;
    }

    @Override
    public void removeSection(int id) {
        if (!this.allocationSet.free(id)) {
            throw new IllegalStateException("Id was not already allocated. id: " + id);
        }
        var oldMetadata = this.sectionMetadata.set(id, null);
        int ptr = oldMetadata.geometryPtr;
        //Free from the heap
        this.usedCapacity -= this.allocationHeap.free(Integer.toUnsignedLong(ptr));
        //Free the upload if it was uploading
        var buf = this.heapUploads.remove(ptr);
        if (buf != null) {
            buf.free();
        }
        this.heapRemoveUploads.add(ptr);
        this.invalidatedIds.add(id);
    }

    private SectionMeta createMeta(BuiltSection section) {
        if ((section.geometryBuffer.size%GEOMETRY_ELEMENT_SIZE)!=0) throw new IllegalStateException();
        int size = (int) (section.geometryBuffer.size/GEOMETRY_ELEMENT_SIZE);
        //clamp size upwards to the allocation granule (upstream 72b3ade6: 128 elements instead of
        // 1024 -> ~20% less geometry memory; VOXY_GEOMETRY_ALLOC_ALIGN=1024 restores the old granule)
        int upsized = (size+(ALLOC_ALIGN-1))&~(ALLOC_ALIGN-1);
        //Address
        int addr = (int)this.allocationHeap.alloc(upsized);
        if (addr == -1) {
            throw new IllegalStateException("Geometry OOM. requested allocation size (in elements): " + size + ", Heap size at top remaining: " + (this.allocationHeap.getLimit()-this.allocationHeap.getSize()) + ", used elements: " + this.usedCapacity);
        }
        this.usedCapacity += upsized;
        //Create upload
        if (this.heapUploads.put(addr, section.geometryBuffer) != null) {
            throw new IllegalStateException("Addr: " + addr);
        }
        this.heapRemoveUploads.remove(addr);
        //Create Meta
        return new SectionMeta(section.position, section.aabb, addr, size, section.offsets, section.childExistence);
    }

    @Override
    public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
        throw new IllegalStateException("Not yet implemented");
    }

    public Int2ObjectOpenHashMap<MemoryBuffer> getUploads() {
        return this.heapUploads;
    }

    public IntOpenHashSet getHeapRemovals() {
        return this.heapRemoveUploads;
    }

    public int getSectionCount() {
        return this.allocationSet.getCount();
    }

    /** True when the arena has a contiguous block for this section right now: a large enough hole, or
     *  enough room left at the top. The used-bytes gate counts holes as free, so a fragmented arena can
     *  pass it and still fail createMeta's alloc (the 'Geometry OOM' throw). */
    public boolean canFit(BuiltSection section) {
        if (section.geometryBuffer == null || section.geometryBuffer.size <= 0) return true;
        long upsized = ((section.geometryBuffer.size / GEOMETRY_ELEMENT_SIZE) + (ALLOC_ALIGN - 1)) & ~(long) (ALLOC_ALIGN - 1);
        if (this.allocationHeap.getLimit() - this.allocationHeap.getSize() >= upsized) return true;
        return this.allocationHeap.numFreeBlocks() > 0 && this.allocationHeap.getLargestFreeBlockSize(0) >= upsized;
    }

    /** Largest hole and the room left at the top, in bytes: [hole, top]. For the fit-gate log. */
    public long[] freeShape() {
        long hole = this.allocationHeap.numFreeBlocks() > 0 ? (long) this.allocationHeap.getLargestFreeBlockSize(0) * GEOMETRY_ELEMENT_SIZE : 0;
        return new long[]{hole, (this.allocationHeap.getLimit() - this.allocationHeap.getSize()) * GEOMETRY_ELEMENT_SIZE};
    }

    public long getGeometryUsedBytes() {
        return this.usedCapacity * GEOMETRY_ELEMENT_SIZE;
    }

    public IntOpenHashSet getUpdateIds() {
        return this.invalidatedIds;
    }

    public void writeMetadata(int sectionId, long ptr) {
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing
            MemoryUtil.memSet(ptr, 0, SECTION_METADATA_SIZE);
        } else {
            sec.writeMetadata(ptr);
        }
    }

    public void writeMetadataSplit(int sectionId, long ptrA, long ptrB) {
        if (SECTION_METADATA_SIZE != 32) {//This system only works with 32 byte metadata
            throw new IllegalStateException();
        }
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing
            MemoryUtil.memSet(ptrA, 0, 16);
            MemoryUtil.memSet(ptrB, 0, 16);
        } else {
            sec.writeMetadataSplitParts(ptrA, ptrB);
        }
    }

    private record SectionMeta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets, byte childExistence) {
        public void writeMetadata(long ptr) {
            this.writeMetadataSplitParts(ptr, ptr+16);
        }

        public void writeMetadataSplitParts(long ptrA, long ptrB) {//First 16 bytes are put into ptrA the remaining 16 bytes are put into ptrB
            //Split the long into 2 ints to solve endian issues
            MemoryUtil.memPutInt(ptrA, (int) (this.position>>32)); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, (int) this.position); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, (int) this.aabb); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, this.geometryPtr + this.offsets[0]); ptrA += 4;

            MemoryUtil.memPutInt(ptrB, (this.offsets[1]-this.offsets[0])|((this.offsets[2]-this.offsets[1])<<16)); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, (this.offsets[3]-this.offsets[2])|((this.offsets[4]-this.offsets[3])<<16)); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, (this.offsets[5]-this.offsets[4])|((this.offsets[6]-this.offsets[5])<<16)); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, (this.offsets[7]-this.offsets[6])|((this.itemCount -this.offsets[7])<<16)); ptrB += 4;
        }
    }
}
