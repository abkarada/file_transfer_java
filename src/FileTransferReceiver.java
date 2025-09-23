import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FileTransferReceiver {	
	public static DatagramChannel channel;
	
	public static final long MAX_FILE_SIZE = 256L << 20;
	public static final int SLICE_SIZE = 1200;
	
	public static void recvFile(){

	}

}

