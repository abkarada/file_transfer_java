import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.MappedByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;

public class FileTransferReceiver {	
	public  DatagramChannel channel;
	public long fileId;
	public int file_size;
	public int total_seq;
	
	public FileChannel fc;
	public Path filePath;
	public MappedByteBuffer mem_buf;
	public static final long MAX_FILE_SIZE = 256L << 20;
	public static final int SLICE_SIZE = 1200;
	public static final int HEADER_SIZE = 22;
	public static final int PACKET_SIZE = SLICE_SIZE + HEADER_SIZE;
	
	public  boolean handshake()
	{
		if(channel == null){
			throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		}
		ByteBuffer rcv_syn = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE)
			.order(ByteOrder.BIG_ENDIAN);

		rcv_syn.clear();
		int r;

		try{
		do{
			r = channel.read(rcv_syn);
			if( r == 0 || r < HandShake_Packet.HEADER_SIZE || r > HandShake_Packet.HEADER_SIZE + 10  || HandShake_Packet.get_signal(rcv_syn) != HandShake_Packet.SYN) LockSupport.parkNanos(200_000);
		}while( r == 0 || r < HandShake_Packet.HEADER_SIZE || r > HandShake_Packet.HEADER_SIZE + 10 || HandShake_Packet.get_signal(rcv_syn) != HandShake_Packet.SYN);
	}catch(IOException e ){System.err.println("IO Error: " +  e);}
		rcv_syn.flip();
		fileId = HandShake_Packet.get_signal(rcv_syn);
		file_size = (int) HandShake_Packet.get_file_Id(rcv_syn);
		total_seq = HandShake_Packet.get_total_seq(rcv_syn);
		
		if(fileId != 0 && file_size != 0 && total_seq != 0)
		 {
			 HandShake_Packet ack_pkt = new HandShake_Packet();
			ack_pkt.make_ACK(fileId, file_size, total_seq);
			try{
			while(channel.write(ack_pkt.get_header().duplicate()) == 0)
			{
				ack_pkt.resetForRetransmitter();
				LockSupport.parkNanos(200_000);
			}}catch(IOException e){System.err.println("IO ERROR: " + e);}
		 	
			rcv_syn.clear();

			int t;
			
			try{
				do{
				t = channel.read(rcv_syn);
				if(t == 0 || t < 9 || t > 13) 
					LockSupport.parkNanos(200_000);
				}while(t == 0 || t < 9 || t > 13);
			}catch(IOException e){
				System.err.println("SYN + ACK Packet State Error: " + e);
			}
			if(HandShake_Packet.get_signal(rcv_syn) == 0x11 && HandShake_Packet.get_file_Id(rcv_syn) == fileId) return true;

		 }

		return false;
	}
	
	public boolean initialize()
	{
		try{
			if(handshake()){
				fc = FileChannel.open(filePath, StandardOpenOption.CREATE 
						, StandardOpenOption.READ
						, StandardOpenOption.WRITE 
						,StandardOpenOption.SYNC);

				fc.truncate(file_size);
				
				 mem_buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, fc.size());

				 return true;

			}
		}catch(IOException e){
			System.err.println("Initialize State Error: " + e);
		}

		return false;
	
	}

	public void ReceiveData(){
	
	initialize();
	
	NackSender sender = new NackSender(channel, fileId, file_size, total_seq, mem_buf);
	Thread t = new Thread(sender, "nack-sender");
	t.start();

	
	}

}
