package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();
    // Failed attempts keep their queue flag and reference: unload cannot discard them.
    private final ConcurrentLinkedDeque<SaveEntry> failedSaves = new ConcurrentLinkedDeque<>();
    private volatile Exception lastFailure;

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    private void processJob() {
        this.save(this.saveQueue.pop());
    }

    private void save(SaveEntry task) {
        var section = task.section;
        section.assertNotFree();
        try {
            // Writes during storage.saveSection mark it dirty again. Keep the queue
            // claim until completion so a second worker cannot overlap this attempt.
            section.setNotDirty();
            task.engine.storage.saveSection(section);
        } catch (Exception e) {
            section.markDirty();
            this.lastFailure = e;
            this.failedSaves.add(task);
            Logger.error("Voxy cache save failed; dirty section retained for retry", e);
            return;
        }
        section.exchangeIsInSaveQueue(false);
        section.release();
    }

    /** Explicit, bounded recovery: each retained attempt is tried once, without a busy retry loop. */
    public synchronized void retryFailedSaves() {
        int count = this.failedSaves.size();
        for (int i = 0; i < count; i++) {
            var task = this.failedSaves.poll();
            if (task != null) this.save(task);
        }
    }

    /*
    public void enqueueSave(WorldSection section) {
        if (section._getSectionTracker() != null && section._getSectionTracker().engine != null) {
            this.enqueueSave(section._getSectionTracker().engine, section);
        } else {
            Logger.error("Tried saving world section, but did not have world associated");
        }
    }*/

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
            }

            //Hard limit the save count to prevent OOM
            if ((!nonBlocking) && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                /*
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }*/
                //If we are still full, process entries in the queue ourselves instead of waiting for the service
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            this.service.execute();
            return true;
        }
        return false;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining.");
            this.service.blockTillEmpty();
        }
        this.service.shutdown();
        //Manually save any remaining entries
        while (!this.saveQueue.isEmpty()) {
            this.processJob();
        }
        this.retryFailedSaves();
        if (!this.failedSaves.isEmpty()) {
            throw new IllegalStateException("Voxy cache shutdown retained " + this.failedSaves.size()
                    + " unsaved dirty sections", this.lastFailure);
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
