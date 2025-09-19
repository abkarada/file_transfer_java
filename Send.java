// UdpAlici.java (ProtocolFamily ile IPv4 Belirtme)
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily; // EKLENDİ
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public class UdpAlici {
    private static final int BUFFER_SIZE = 1028;

    public static void main(String[] args) {
        // System.setProperty satırı KALDIRILDI.
        final int PORT = 9999;

        // DEĞİŞİKLİK: Kanal açılırken IPv4 (INET) ailesi belirtildi.
        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(PORT));
            System.out.println("[*] Port basariyla acildi (IPv4). Dinleme baslatildi: " + channel.getLocalAddress());

            // Kodun geri kalanı tamamen aynı...
            while (true) {
                System.out.println("\n[*] Yeni bir transfer talebi (REQ) bekleniyor...");
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                SocketAddress senderAddress = channel.receive(buffer);
                buffer.flip();
                String request = StandardCharsets.UTF_8.decode(buffer).toString();
                String fileName;
                int totalPackets;
                if (request.startsWith("REQ:")) {
                    try {
                        String[] parts = request.split(":");
                        fileName = parts[1];
                        totalPackets = Integer.parseInt(parts[2]);
                        System.out.println("[+] Transfer talebi alindi: " + senderAddress);
                        System.out.println("    Dosya Adi: " + fileName);
                        System.out.println("    Toplam Paket: " + totalPackets);
                    } catch (Exception e) {
                        System.err.println("[!] Gecersiz REQ paketi alindi. Paket atlandi.");
                        continue;
                    }
                } else {
                    System.err.println("[!] Beklenmedik bir paket alindi (REQ degil). Paket atlandi.");
                    continue;
                }
                ByteBuffer ackBuffer = ByteBuffer.wrap("ACK_REQ".getBytes(StandardCharsets.UTF_8));
                channel.send(ackBuffer, senderAddress);
                System.out.println("[*] Talep onayi (ACK_REQ) gonderildi.");
                Map<Integer, byte[]> receivedData = new TreeMap<>();
                channel.socket().setSoTimeout(20000);
                try {
                    while (receivedData.size() < totalPackets) {
                        buffer.clear();
                        channel.receive(buffer);
                        buffer.flip();
                        int sequenceNumber = buffer.getInt();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        receivedData.put(sequenceNumber, data);
                        System.out.print("\r[+] Alinan paket sayisi: " + receivedData.size() + "/" + totalPackets);
                    }
                } catch (IOException e) {
                    System.err.println("\n[!] Veri alimi sirasinda zaman asimi! Transfer iptal edildi.");
                    continue;
                } finally {
                    channel.socket().setSoTimeout(0);
                }
                System.out.println("\n[*] Paket alimi tamamlandi. Dosya yaziliyor...");
                String finalStatus;
                try (FileOutputStream fos = new FileOutputStream(fileName)) {
                    for (int i = 0; i < totalPackets; i++) {
                        byte[] data = receivedData.get(i);
                        if (data == null) {
                            throw new IOException("Eksik paket: #" + i);
                        }
                        fos.write(data);
                    }
                    System.out.println("[*] Dosya basariyla yazildi: " + fileName);
                    finalStatus = "FIN_SUCCESS: Dosya tam ve saglam bir sekilde alindi.";
                } catch (IOException e) {
                    finalStatus = "FIN_FAILURE: " + e.getMessage();
                }
                ByteBuffer responseBuffer = ByteBuffer.wrap(finalStatus.getBytes(StandardCharsets.UTF_8));
                channel.send(responseBuffer, senderAddress);
                System.out.println("[*] Gondericiye bitis durumu (" + (finalStatus.startsWith("FIN_SUCCESS") ? "SUCCESS" : "FAILURE") + ") gonderildi.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}