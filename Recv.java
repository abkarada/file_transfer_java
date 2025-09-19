import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily; // EKLENDİ
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public class Recv {
    private static final int DATA_SIZE = 1024;
    private static final int BUFFER_SIZE = 1028;

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Kullanim: java UdpGonderici <hedef_ip_adresi> <dosya_yolu>");
            return;
        }
        String hostIp = args[0];
        String filePath = args[1];
        final int PORT = 9999;
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("Hata: " + filePath + " dosyasi bulunamadi.");
            return;
        }
        System.out.println("[*] Hedef: " + hostIp + ":" + PORT);
        System.out.println("[*] Gonderilecek dosya: " + file.getName() + " (" + file.length() + " bytes)");

        // DEĞİŞİKLİK: Kanal açılırken IPv4 (INET) ailesi belirtildi.
        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
             FileInputStream fis = new FileInputStream(file);
             FileChannel fileChannel = fis.getChannel()) {

            channel.socket().setSoTimeout(5000);
            InetSocketAddress targetAddress = new InetSocketAddress(hostIp, PORT);
            long totalPackets = (long) Math.ceil((double) file.length() / DATA_SIZE);
            if (file.length() == 0) totalPackets = 1;

            String request = "REQ:" + file.getName() + ":" + totalPackets;
            ByteBuffer requestBuffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            ByteBuffer ackBuffer = ByteBuffer.allocate(64);

            boolean ackReceived = false;
            for (int i = 0; i < 3; i++) {
                System.out.println("[*] Transfer talebi gonderiliyor (Deneme " + (i + 1) + "/3)...");
                channel.send(requestBuffer.rewind(), targetAddress);
                try {
                    channel.receive(ackBuffer);
                    ackBuffer.flip();
                    String ack = StandardCharsets.UTF_8.decode(ackBuffer).toString();
                    if (ack.equals("ACK_REQ")) {
                        System.out.println("[+] Alicidan talep onayi (ACK_REQ) alindi. Transfere baslaniyor.");
                        ackReceived = true;
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    System.err.println("[!] Onay bekleme zamani doldu.");
                }
            }
            if (!ackReceived) {
                System.err.println("[!] Alicidan onay alinamadi. Islem iptal edildi.");
                return;
            }
            int sequenceNumber = 0;
            ByteBuffer dataBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            while (true) {
                dataBuffer.clear();
                dataBuffer.putInt(sequenceNumber);
                int bytesRead = fileChannel.read(dataBuffer.slice());
                if (bytesRead <= 0 && file.length() > 0) break;

                dataBuffer.flip();
                dataBuffer.limit(bytesRead + 4);
                channel.send(dataBuffer, targetAddress);
                System.out.print("\r[+] Gonderilen paket sayisi: " + (sequenceNumber + 1) + "/" + totalPackets);
                sequenceNumber++;
                if (file.length() == 0) break;
            }
            System.out.println("\n[*] Tum paketler gonderildi.");
            System.out.println("[*] Alicidan nihai sonuc bekleniyor (30 saniye zaman asimi)...");
            channel.socket().setSoTimeout(30000);
            ByteBuffer finalResponseBuffer = ByteBuffer.allocate(1024);

            try {
                channel.receive(finalResponseBuffer);
                finalResponseBuffer.flip();
                String response = StandardCharsets.UTF_8.decode(finalResponseBuffer).toString();
                System.out.println("\n--- TRANSFER SONUCU ---");
                System.out.println(response);
                System.out.println("-----------------------");
            } catch (SocketTimeoutException e) {
                System.err.println("\n[!] Hata: Alicidan nihai sonuc alinamadi. Transfer basarisiz olmus olabilir.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
