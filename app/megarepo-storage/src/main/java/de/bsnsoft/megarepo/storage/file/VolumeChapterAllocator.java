package de.bsnsoft.megarepo.storage.file;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public class VolumeChapterAllocator {

    private static final int BLOBS_PER_CHAPTER = 1000;
    private static final int CHAPTERS_PER_VOLUME = 1000;

    private final Path contentDir;
    private final AtomicLong counter;

    public VolumeChapterAllocator(Path contentDir, long initialCount) {
        this.contentDir = contentDir;
        this.counter = new AtomicLong(initialCount);
    }

    public Path allocate() {
        long index = counter.getAndIncrement();
        long chapter = index % CHAPTERS_PER_VOLUME;
        long volume = (index / CHAPTERS_PER_VOLUME) % CHAPTERS_PER_VOLUME;

        String volDir = String.format("vol-%02d", volume);
        String chapDir = String.format("chap-%03d", chapter);

        return contentDir.resolve(volDir).resolve(chapDir);
    }
}
