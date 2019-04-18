package ua.lokha.megachunkfixer2000;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;

import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class RegionFile implements Closeable {

    public static final int CHUNK_HEADER_SIZE = 5;
    public static final int VERSION_GZIP = 1;
    public static final int VERSION_DEFLATE = 2;
    public static final int SECTOR_BYTES = 4096;
    public static final int SECTOR_INTS = SECTOR_BYTES / 4;
    public static final byte emptySector[] = new byte[4096];

    public final File fileName;
    public int offsets[];
    public int chunkTimestamps[];
    public RandomAccessFile file;
    public ArrayList<Boolean> sectorFree;
    public int sizeDelta;
    public long lastModified = 0;

    @SneakyThrows
    public RegionFile(File path) {
        fileName = path;
        debugln("REGION LOAD " + fileName);

        if (path.exists()) {
            lastModified = path.lastModified();
        }

        file = new RandomAccessFile(path, "rw");

        this.recreateIndexes();
    }

    @SneakyThrows
    private void recreateIndexes() {
        file.seek(0);

        sizeDelta = 0;
        offsets = new int[SECTOR_INTS];
        chunkTimestamps = new int[SECTOR_INTS];

        if (file.length() < SECTOR_BYTES) {
            /* we need to write the chunk offset table */
            for (int i = 0; i < SECTOR_INTS; ++i) {
                file.writeInt(0);
            }
            // write another sector for the timestamp info
            for (int i = 0; i < SECTOR_INTS; ++i) {
                file.writeInt(0);
            }

            sizeDelta += SECTOR_BYTES * 2;
        }

        if ((file.length() & 0xfff) != 0) {
            /* the file size is not a multiple of 4KB, grow it */
            for (int i = 0; i < (file.length() & 0xfff); ++i) {
                file.write((byte) 0);
            }
        }

        /* set up the available sector map */
        int nSectors = (int) file.length() / SECTOR_BYTES;
        sectorFree = new ArrayList<>(nSectors);

        for (int i = 0; i < nSectors; ++i) {
            sectorFree.add(true);
        }

        sectorFree.set(0, false); // chunk offset table
        sectorFree.set(1, false); // for the last modified info

        file.seek(0);
        for (int i = 0; i < SECTOR_INTS; ++i) {
            int offset = file.readInt();
            offsets[i] = offset;
            if (offset != 0 && (offset >> 8) + (offset & 0xFF) <= sectorFree.size()) {
                for (int sectorNum = 0; sectorNum < (offset & 0xFF); ++sectorNum) {
                    sectorFree.set((offset >> 8) + sectorNum, false);
                }
            }
        }
        for (int i = 0; i < SECTOR_INTS; ++i) {
            int lastModValue = file.readInt();
            chunkTimestamps[i] = lastModValue;
        }
    }

    @SneakyThrows
    public void clearUnusedSpace() {
        List<ChunkOffset> offsets = new ArrayList<>(this.offsets.length);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (this.hasChunk(x, z)) {
                    offsets.add(new ChunkOffset(this.getOffset(x, z), x, z));
                }
            }
        }

        offsets.sort(Comparator.comparingInt(ChunkOffset::getOffset));

        int chunkMoveTotal = 0;

        int nextPos = 2 * SECTOR_BYTES;
        for (ChunkOffset chunkOffset : offsets) {
            int offset = chunkOffset.getOffset();

            int pos = (offset >> 8) * SECTOR_BYTES;

            file.seek(pos);
            int length = file.readInt();
            int sectorsNeeded = (length - 1 + CHUNK_HEADER_SIZE) / SECTOR_BYTES + 1;

            if (pos > nextPos) {
                chunkMoveTotal++;
                byte[] bytes = new byte[length];
                file.read(bytes);

                file.seek(nextPos);
                file.writeInt(length);
                file.write(bytes, 0, bytes.length);

                int nextOffset = (((nextPos / SECTOR_BYTES) << 8) | sectorsNeeded);
                this.setOffset(chunkOffset.getX(), chunkOffset.getZ(), nextOffset);
            } else if (pos < nextPos) {
                System.out.println("WOW");
            }

            nextPos += sectorsNeeded * SECTOR_BYTES;
        }

        if (file.length() > nextPos) {
            System.out.println("Сокращаем длину региона " + this.getFileName() + " " +
                    "с " + Utils.toLogLength(file.length()) + " " +
                    "до " + Utils.toLogLength(nextPos) + " " +
                    "(-" + Utils.toLogPercent(nextPos, file.length()) + "%), " +
                    "было перемещено " + chunkMoveTotal + " чанков.");
            file.setLength(nextPos);
        }

        this.recreateIndexes();
    }

    @Data
    @AllArgsConstructor
    private static class ChunkOffset {
        private int offset;
        private int x;
        private int z;
    }

    public String getFileName() {
        return fileName.getName();
    }

    /* the modification date of the region file when it was first opened */
    public long lastModified() {
        return lastModified;
    }

    /* gets how much the region file has grown since it was last checked */
    public synchronized int getSizeDelta() {
        int ret = sizeDelta;
        sizeDelta = 0;
        return ret;
    }

    // various small debug printing helpers
    private void debug(String in) {
//        System.out.print(in);
    }

    private void debugln(String in) {
        debug(in + "\n");
    }

    private void debug(String mode, int x, int z, String in) {
        debug("REGION " + mode + " " + fileName.getName() + "[" + x + "," + z + "] = " + in);
    }

    private void debug(String mode, int x, int z, int count, String in) {
        debug("REGION " + mode + " " + fileName.getName() + "[" + x + "," + z + "] " + count + "B = " + in);
    }

    private void debugln(String mode, int x, int z, String in) {
        debug(mode, x, z, in + "\n");
    }

    /*
     * gets an (uncompressed) stream representing the chunk data returns null if
     * the chunk is not found or an error occurs
     */
    @SneakyThrows
    public synchronized DataInputStream getChunkDataInputStream(int x, int z) {
        if (outOfBounds(x, z)) {
            debugln("READ", x, z, "out of bounds");
            return null;
        }

        int offset = getOffset(x, z);
        if (offset == 0) {
            // debugln("READ", x, z, "miss");
            return null;
        }

        int sectorNumber = offset >> 8;
        int numSectors = offset & 0xFF;

        if (sectorNumber + numSectors > sectorFree.size()) {
            debugln("READ", x, z, "invalid sector");
            return null;
        }

        file.seek(sectorNumber * SECTOR_BYTES);
        int length = file.readInt();

        if (length > SECTOR_BYTES * numSectors) {
            debugln("READ", x, z, "invalid length: " + length + " > 4096 * " + numSectors);
            return null;
        }

        byte version = file.readByte();
        if (version == VERSION_GZIP) {
            byte[] data = new byte[length - 1];
            file.read(data);
            // debug("READ", x, z, " = found");
            return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)));
        } else if (version == VERSION_DEFLATE) {
            byte[] data = new byte[length - 1];
            file.read(data);
            // debug("READ", x, z, " = found");
            return new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)));
        }

        debugln("READ", x, z, "unknown version " + version);
        return null;
    }

    public DataOutputStream getChunkDataOutputStream(int x, int z) {
        if (outOfBounds(x, z)) {
            return null;
        }

        return new DataOutputStream(new DeflaterOutputStream(new ChunkBuffer(x, z)));
    }

    /* write a chunk at (x,z) with length bytes of data to disk */
    @SneakyThrows
    protected synchronized void write(int x, int z, byte[] data, int length) {
        int offset = getOffset(x, z);
        int sectorNumber = offset >> 8;
        int sectorsAllocated = offset & 0xFF;
        int sectorsNeeded = (length + CHUNK_HEADER_SIZE) / SECTOR_BYTES + 1;

        // maximum chunk size is 1MB
        if (sectorsNeeded >= 256) {
            return;
        }

        if (sectorNumber != 0 && sectorsAllocated == sectorsNeeded) {
            /* we can simply overwrite the old sectors */
            debug("SAVE", x, z, length, "rewrite");
            write(sectorNumber, data, length);
        } else {
            /* we need to allocate new sectors */

            /* mark the sectors previously used for this chunk as free */
            for (int i = 0; i < sectorsAllocated; ++i) {
                sectorFree.set(sectorNumber + i, true);
            }

            /* scan for a free space large enough to store this chunk */
            int runStart = sectorFree.indexOf(true);
            int runLength = 0;
            if (runStart != -1) {
                for (int i = runStart; i < sectorFree.size(); ++i) {
                    if (runLength != 0) {
                        if (sectorFree.get(i)) {
                            runLength++;
                        } else {
                            runLength = 0;
                        }
                    } else if (sectorFree.get(i)) {
                        runStart = i;
                        runLength = 1;
                    }
                    if (runLength >= sectorsNeeded) {
                        break;
                    }
                }
            }

            if (runLength >= sectorsNeeded) {
                /* we found a free space large enough */
                debug("SAVE", x, z, length, "reuse");
                sectorNumber = runStart;
                setOffset(x, z, (sectorNumber << 8) | sectorsNeeded);
                for (int i = 0; i < sectorsNeeded; ++i) {
                    sectorFree.set(sectorNumber + i, false);
                }
                write(sectorNumber, data, length);
            } else {
                /*
                 * no free space large enough found -- we need to grow the
                 * file
                 */
                debug("SAVE", x, z, length, "grow");
                file.seek(file.length());
                sectorNumber = sectorFree.size();
                for (int i = 0; i < sectorsNeeded; ++i) {
                    file.write(emptySector);
                    sectorFree.add(false);
                }
                sizeDelta += SECTOR_BYTES * sectorsNeeded;

                write(sectorNumber, data, length);
                setOffset(x, z, (sectorNumber << 8) | sectorsNeeded);
            }
        }
        setTimestamp(x, z, (int) (System.currentTimeMillis() / 1000L));
    }

    /* write a chunk data to the region file at specified sector number */
    @SneakyThrows
    private void write(int sectorNumber, byte[] data, int length) {
        debugln(" " + sectorNumber);
        file.seek(sectorNumber * SECTOR_BYTES);
        file.writeInt(length + 1); // chunk length
        file.writeByte(VERSION_DEFLATE); // chunk version number
        file.write(data, 0, length); // chunk data
    }

    /* is this an invalid chunk coordinate? */
    private boolean outOfBounds(int x, int z) {
        return x < 0 || x >= 32 || z < 0 || z >= 32;
    }

    public int getOffset(int x, int z) {
        return offsets[x + z * 32];
    }

    public boolean hasChunk(int x, int z) {
        return getOffset(x, z) != 0;
    }

    @SneakyThrows
    public void setOffset(int x, int z, int offset) {
        offsets[x + z * 32] = offset;
        file.seek((x + z * 32) * 4);
        file.writeInt(offset);
    }

    @SneakyThrows
    private void setTimestamp(int x, int z, int value) {
        chunkTimestamps[x + z * 32] = value;
        file.seek(SECTOR_BYTES + (x + z * 32) * 4);
        file.writeInt(value);
    }

    @SneakyThrows
    public synchronized void deleteChunk(int x, int z) {
        setOffset(x, z, 0);
        setTimestamp(x, z, 0);
        debug("Region deleted chunk");
    }

    @Override
    @SneakyThrows
    public void close() {
        file.close();
    }

    /*
     * lets chunk writing be multithreaded by not locking the whole file as a
     * chunk is serializing -- only writes when serialization is over
     */
    class ChunkBuffer extends ByteArrayOutputStream {

        private int x, z;

        public ChunkBuffer(int x, int z) {
            super(8096); // initialize to 8KB
            this.x = x;
            this.z = z;
        }

        @Override
        public void close() {
            RegionFile.this.write(x, z, buf, count);
        }
    }

    public static byte[] getEmptySector() {
        return emptySector;
    }

    public int[] getOffsets() {
        return offsets;
    }

    public int[] getChunkTimestamps() {
        return chunkTimestamps;
    }

    public RandomAccessFile getFile() {
        return file;
    }

    public ArrayList<Boolean> getSectorFree() {
        return sectorFree;
    }

    public long getLastModified() {
        return lastModified;
    }
}