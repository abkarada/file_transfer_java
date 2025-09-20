import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;

public class FileTransferSender {
    public static DatagramChannel channel; 

    public static final long TURBO_MAX  = 256L << 20; 
    public static final int  SLICE_SIZE = 1200;
    public static final int  MAX_TRY    = 4;
    public static final int  BACKOFF_NS = 200_000;

    public static void sendFile(Path filePath, long fileId) throws IOException {
        if (channel == null) {
            throw new IllegalStateException("DatagramChannel null:First set this channel as connected.");
        }

        try (FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)) {
            long fileSize = fc.size();
            if (fileSize > TURBO_MAX) {
                throw new IllegalArgumentException("Turbo Mode only support ≤256 MB files.");
            }

            MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);

            for (int i = 0; i < MAX_TRY && !mem.isLoaded(); i++) {
                mem.load();
            }

            int totalSeq = (int) ((fileSize + SLICE_SIZE - 1) / SLICE_SIZE);

            CRC32C crc = new CRC32C();
            CRC32C_Packet pkt = new CRC32C_Packet();

            ByteBuffer[] frame = new ByteBuffer[2];
            frame[0] = pkt.headerBuffer();

            int seqNo = 0;
            for (int off = 0; off < mem.capacity(); ) {
                int remaining = mem.capacity() - off;
                int take = Math.min(SLICE_SIZE, remaining);

                ByteBuffer payload = mem.slice(off, take);

                crc.reset();
                crc.update(payload.duplicate());
                int crc32c = (int) crc.getValue();

                pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);

                payload.position(0).limit(take);
                frame[1] = payload;

                int wrote;
                do {
                    wrote = channel.write(frame); 
                    if (wrote == 0) {
                        pkt.resetForRetry();
                        payload.position(0).limit(take);
                        LockSupport.parkNanos(BACKOFF_NS);
                    }
                } while (wrote == 0);

                off += take;
                seqNo++;
            }
        }
    }

    public static void main(String[] args) {
    }
}

