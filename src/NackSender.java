import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.BitSet;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;

public class NackSender implements Runnable{
	public final long fileId;
	public final int file_size;
	public final int total_seq;
	public final DatagramChannel channel;
	public final BitSet recv;
	public final NackFrame frame;
	public NackSender(DatagramChannel channel, long fileId, int file_size,
			int total_seq){
		this.channel = channel;
		this.fileId = fileId;
		this.file_size = file_size;
		this.total_seq = total_seq;
		this.recv = new BitSet(total_seq);
		this.frame = new NackFrame();
	}

	public volatile int cum_Ack = 0;
	public final int CRC32C_HEADER_SIZE = 22;
	public final int PAYLOAD_SIZE = 1200;
	public final int TOTAL_PACKET_SIZE = 1222;

    	public static final int OFF_FILE_ID  = 0;
    	public static final int OFF_SEQ      = 8;
    	public static final int OFF_TOTAL    = 12;
    	public static final int OFF_PLEN     = 16;


	public  ByteBuffer buf = ByteBuffer.allocateDirect(CRC32C_HEADER_SIZE + PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN);
	public CRC32C crc = new CRC32C();
	
	private void updateCumulativeAck() {
    	synchronized(this) {
    	    while(cum_Ack < total_seq && recv.get(cum_Ack)) {
   	         cum_Ack++;
  	      }
 	   }
	}

	public void onData(ByteBuffer fullPacket){
		int seqNo = CRC32C_Packet.seqNo(fullPacket);
		int receivedCrc = CRC32C_Packet.crc32(fullPacket);
		int payloadLen = CRC32C_Packet.plen(fullPacket);

		ByteBuffer payload = fullPacket.slice(CRC32C_Packet.HEADER_SIZE, payloadLen);

		crc.reset();
		crc.update(payload.duplicate());
		int calculatedCrc = (int) crc.getValue();

		if(calculatedCrc == receivedCrc){
			recv.set(seqNo);
			updateCumulativeAck();
		}
		else{
			recv.clear(seqNo);
		}

	}

	public long build64(){
		long mask = 0L;
		int base = cum_Ack;
		for(int i = 0; i < 64; i++)
		{
			if(base + i >= total_seq) break;
			if(recv.get(base + i))
					mask |= (1L << i);
		}
		return mask;
	}
	

	public void send_Nack_Frame(){
		long mask = build64();
		frame.fill(fileId, cum_Ack, mask);

		int r;
		try{
			do{
				r = channel.write(frame.buffer().duplicate());
			if(r == 0) LockSupport.parkNanos(200_000);
		}while(r == 0);
	}catch(IOException e){
		throw new RuntimeException("NACK write failed", e);
		}
	}
	
	ThreadFactory daemonFactory = r -> {
		Thread t = new Thread(r, "nack-scheduler");
		t.setDaemon(true);
		return t;
	};

	public final ScheduledExecutorService scheduler = 
		Executors.newScheduledThreadPool(1, daemonFactory);

	public final Runnable nack_service = () -> {
		try{
			send_Nack_Frame();
		}catch(Exception e){
			System.err.println("Thread Error[nack-scheduler]: " + e); 
		}
	};

	private ScheduledFuture<?> nackHandle;

	public void startNackLoop()
	{
		if(nackHandle == null || nackHandle.isCancelled() || nackHandle.isDone())
		{
		  nackHandle = scheduler.scheduleAtFixedRate(nack_service, 0 , 50, TimeUnit.MILLISECONDS);
		}
	}
	public void stopNackLoop(){
		if(nackHandle != null){
			nackHandle.cancel(false);
			nackHandle = null;
		}
	}
	
	public void shutdownScheduler(){
		scheduler.shutdown();
	}

	@Override
	public void run(){
		if(channel == null)
		{
			throw new IllegalStateException("You must bind the channel first");
		}
		
	while(!Thread.currentThread().isInterrupted()){
		buf.clear();

		int x;
		do{
			try{
			x = channel.read(buf);
			}catch(IOException e){
				System.err.println("read failed: " + e);
				return ;
			}
			if( x == 0){
				LockSupport.parkNanos(200_000);
			}
		}while(x == 0);
	
	buf.flip();		

	if (x < CRC32C_HEADER_SIZE || x > TOTAL_PACKET_SIZE || buf.getLong(OFF_FILE_ID) != fileId) {
    		buf.clear();
    	continue;
	}


		onData(buf);
		startNackLoop();
		buf.clear();
		}

		stopNackLoop();
		
	}
}
