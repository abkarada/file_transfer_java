import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SHA256_Packet {
	public static final int OFF_SIGNAL = 0;
	public static final int OFF_FILE_ID = 1;
	public static final int OFF_FILE_SIZE = 9;
	public static final int OFF_TOTAL_SEQ = 17;
	public static final int OFF_SIGNATURE = 21;
	
	public static final byte SYN = 0x01;
	public static final byte ACK = 0x10;
	public static final int HEADER_SIZE = 53;
	
	private final ByteBuffer header;
	
	public SHA256_Packet() {
		this.header = ByteBuffer.allocateDirect(53).order(ByteOrder.BIG_ENDIAN);
	}
	
	public ByteBuffer get_header()
	{
		return header;
	}
	
	public void fill_signature(long fileId, long fileSize, int totalSeq, byte[] signature) {
		header.clear();
		
		header.put(OFF_SIGNAL, SYN);
		header.putLong(OFF_FILE_ID, fileId);
		header.putLong(OFF_FILE_SIZE, fileSize);
		header.putInt(OFF_TOTAL_SEQ, totalSeq);
		
		header.position(OFF_SIGNATURE);
		header.put(signature);
		
		header.limit(HEADER_SIZE);
		header.position(0);
		
	}

	public void make_ack(long fileId, long fileSize, int totalSeq){
		header.clear();
	
		header.put(OFF_SIGNAL, ACK);
		header.putLong(OFF_FILE_ID, fileId);
		header.putLong(OFF_FILE_SIZE, fileSize);
		header.putInt(OFF_TOTAL_SEQ, totalSeq);

		header.limit(21);
		header.position(0);

	}
	
	public void reset_for_retransmitter() {
			header.position(0).limit(HEADER_SIZE);
	}

}
