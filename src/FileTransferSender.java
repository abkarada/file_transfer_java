import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*; 
import java.nio.file.*;


public class FileTransferSender {
	public static DatagramChannel channel;
	public static String FILE_PATH;
	public static final long turbo_max = 256L << 20;
	public static final int slice_size = 1200;
	public static final int max_try = 4;
	
	public FileTransferSender(DatagramChannel channel, String FILE_PATH){
		this.channel = channel;
		this.FILE_PATH = FILE_PATH;
	}

	public static void main(String[] args)
	{
		FileChannel fc = FileChannel.open(FILE_PATH, StandardOpenOptions.READ);
		if(!(fc.size() > turbo_max)){
			MappedByteBuffer mem_buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
		
			for(int i = 0; i < max_try && mem_buf.isLoaded() == false; i++)
			{
				mem_buf.load();
			}
			
			for(int off = 0; off < mem_buf.capacity(); off += Math.min(slice_size, 
						mem_buf.capacity() - off))
			{
				ByteBuffer buf = mem_buf.slice(off, slice_size);

				while(channel.write(buf) == 0){
					java.util.concurrent.locks.LockSupport.parkNanos(200_000);
				}
			}

			}

		}

}
