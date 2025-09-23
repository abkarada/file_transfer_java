import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileTransferSender {
	    public static DatagramChannel channel;

	    public static final long TURBO_MAX  = 256L << 20; // 256 MB
	    public static final int  SLICE_SIZE = 1200;
	    public static final int  MAX_TRY    = 4;
	    public static final int  BACKOFF_NS = 200_000;
	

		public static boolean handshake(long fileId, int file_size, int total_seq) throws IOException {
		if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		long candidate_file_Id = -1;
		HandShake_Packet pkt = new HandShake_Packet();
		pkt.make_SYN(fileId, file_size, total_seq);
	
		channel.write(pkt.get_header().duplicate());
		ByteBuffer buffer = ByteBuffer.allocateDirect(pkt.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
		int r;
		do{
			r = channel.read(buffer);
			if(r <= 0) LockSupport.parkNanos(200_000);
		}while( r <= 0);
		buffer.flip();
		if(r >= pkt.HEADER_SIZE && buffer.get(0) == 0x10){
			candidate_file_Id = buffer.getLong(1); 
		}

		return candidate_file_Id == fileId;
		
		}
	    public static void sendOne(CRC32C crc, CRC32C_Packet pkt,
                MappedByteBuffer mem, long fileId,
                int seqNo, int totalSeq, int take, int off) throws IOException{
	    	
	    	ByteBuffer payload = mem.slice(off, take);
	    	crc.reset();
	    	crc.update(payload.duplicate());
	    	int crc32c = (int) crc.getValue();
	    	
	    	pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);
	    	
	        ByteBuffer[] frame = new ByteBuffer[]{ pkt.headerBuffer(), payload.position(0).limit(take) };
		
	        int wrote;
	        do {
	        	wrote = (int)channel.write(frame);
	        	if(wrote == 0) {
	        		pkt.resetForRetry();
	        		payload.position(0).limit(take);
	        		LockSupport.parkNanos(BACKOFF_NS);
	        	}
	        } while(wrote == 0);
	    	
	    }
	    
	    public static void sendFile(Path filePath, long fileId) throws IOException, NoSuchAlgorithmException{
	    	if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
	    	
	    	try(FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)){
	    		long fileSize = fc.size();
	    		if(fileSize > TURBO_MAX) throw new IllegalArgumentException("Turbo Mode is only for  ≤256 MB.");
	    		
	    		MappedByteBuffer mem = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
	    		for(int i = 0; i < MAX_TRY && !mem.isLoaded(); i++) mem.load();
	    		
	    		int totalSeq = (int) ((fileSize + SLICE_SIZE - 1) / SLICE_SIZE);
	    		
	    		CRC32C crc = new CRC32C();
	    		CRC32C_Packet pkt = new CRC32C_Packet();
	    		MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
	    		
			long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
			final long MAX_BACKOFF = 10_000_000L;
			long backoff  = 1_000_000L;
			boolean hand_shaking;
			do{
				hand_shaking = handshake(fileId, (int) fileSize, totalSeq);
				if(hand_shaking) break;

				if(Thread.currentThread().isInterrupted()){
					throw new IllegalStateException("Handshake Thread interrupted");
				}
				if(System.nanoTime() > deadline){
					throw new IllegalStateException("Handshake timeout");
				}
				LockSupport.parkNanos(backoff);
				 if (backoff < MAX_BACKOFF) {
					   backoff = Math.min(MAX_BACKOFF, backoff << 1);
					}
			}while(!hand_shaking);

	    		ConcurrentLinkedQueue<Integer> retxQueue = new ConcurrentLinkedQueue<>();
	    		 Thread nackThread = new Thread(new NackListener(channel, fileId, totalSeq, retxQueue, BACKOFF_NS),
                         "nack-listener");
	    		 
	    		 nackThread.setDaemon(true);
	    		 nackThread.start();
	    		
	    		int seqNo = 0;
	    		for(int off = 0; off < mem.capacity(); ){
	    			int remaining = mem.capacity() - off;
	    			int take  = Math.min(SLICE_SIZE, remaining);
	    			
	    			ByteBuffer payloadForSha = mem.slice(off, take);
	    			sha256.update(payloadForSha.duplicate());
	    			
	                sendOne(crc, pkt, mem, fileId, seqNo, totalSeq, take, off);
	                
	                off += take;
	                seqNo++;
	    		}
	    		
	    		long Deadline = System.nanoTime() + 3_000_000_000L;
	    		while(System.nanoTime() < Deadline) {
	    			Integer miss = retxQueue.poll();
	    			if(miss == null) {
	    				LockSupport.parkNanos(200_000);
	    				continue;
	    			}
	    			
	    			int off = miss*SLICE_SIZE;
	    			int take = Math.min(SLICE_SIZE, mem.capacity() - off);
	    			if(take > 0) {
	    				sendOne(crc, pkt, mem, fileId, miss, totalSeq, take, off);
	    				}
	    			}
	    		byte[] sha = sha256.digest();
	    		SHA256_Packet sign_pkt = new SHA256_Packet();
	    		sign_pkt.fill_signature(fileId, fileSize, totalSeq, sha);
	    		
	    		while(channel.write(sign_pkt.get_header().duplicate()) == 0) {
	    			sign_pkt.reset_for_retransmitter();
	    			LockSupport.parkNanos(BACKOFF_NS);
	    		}
				
				ByteBuffer receive = ByteBuffer.allocateDirect(21);
				int r;
				try{
					do{
				r = channel.read(receive);
				if(r == 0 || receive.get(0) != 0x10 || receive.getLong(1) != fileId) LockSupport.parkNanos(200_000);
					}while(r == 0 || receive.get(0) != 0x10 || receive.getLong(1) != fileId);
				}catch(IOException e){
					System.err.println("SHAttered: " + e);
				}
				

					
	    		}
	    		
	    		
	    	}
	    }

