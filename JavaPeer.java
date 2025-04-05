package rkayyo;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class JavaPeer {
    private static final int PORT = 5001;
    private static final String SERVICE_TYPE = "_p2pfile._tcp.local.";
    private static JmDNS jmdns;
    private static String sharedDir = "C:\\Users\\ryank\\OneDrive\\Documents\\CISC 468\\share_p2p_java";
    private static final Map<String, String> discoveredPeers = new HashMap<>();
    private static final Map<String, List<String>> peerFileLists = new HashMap<>();
    private static final Scanner scanner = new Scanner(System.in);
    private static volatile boolean awaitingConsent = false;
    private static volatile String consentResponse = null;

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0) {
            sharedDir = args[0];
        }
        File dir = new File(sharedDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        System.out.println("Using shared directory: " + dir.getAbsolutePath());

        try {
            InetAddress localAddr = getWifiIPv4Address();
            jmdns = JmDNS.create(localAddr);

            ServiceInfo serviceInfo = ServiceInfo.create(SERVICE_TYPE, "JavaPeer", PORT, "Secure File Sharing");
            jmdns.registerService(serviceInfo);
            System.out.println("Registered JavaPeer at " + localAddr.getHostAddress() + ":" + PORT);

            Thread serverThread = new Thread(JavaPeer::runServer);
            serverThread.start();

            jmdns.addServiceListener(SERVICE_TYPE, new SampleListener());
            jmdns.requestServiceInfo(SERVICE_TYPE, "PythonPeer", 1000);
            System.out.println("Java discovery started on " + localAddr.getHostAddress());

            System.out.println("Commands: list <peer>, request <peer> <filename>, send <peer> <filename>, exit");
            while (scanner.hasNextLine()) {
                if (!awaitingConsent) {
                    System.out.print("Enter command: ");
                    String input = scanner.nextLine().trim();
                    String[] parts = input.split("\\s+");

                    if (parts.length == 0) continue;

                    switch (parts[0].toLowerCase()) {
                        case "list":
                            if (parts.length != 2) {
                                System.out.println("Usage: list <peer>");
                                break;
                            }
                            String listPeer = parts[1];
                            String listPeerKey = findPeerKey(listPeer);
                            if (listPeerKey != null) {
                                String[] peerParts = listPeerKey.split(":");
                                requestFileList(peerParts[0], Integer.parseInt(peerParts[1]), listPeer);
                            } else {
                                System.out.println("Peer not found: " + listPeer + ". Discovered peers: " + discoveredPeers.values());
                            }
                            break;

                        case "request":
                            if (parts.length != 3) {
                                System.out.println("Usage: request <peer> <filename>");
                                break;
                            }
                            String reqPeer = parts[1];
                            String reqFile = parts[2];
                            String reqPeerKey = findPeerKey(reqPeer);
                            if (reqPeerKey != null) {
                                String[] reqParts = reqPeerKey.split(":");
                                requestFile(reqParts[0], Integer.parseInt(reqParts[1]), reqPeer, reqFile);
                            } else {
                                System.out.println("Peer not found: " + reqPeer + ". Discovered peers: " + discoveredPeers.values());
                            }
                            break;

                        case "send":
                            if (parts.length != 3) {
                                System.out.println("Usage: send <peer> <filename>");
                                break;
                            }
                            String sendPeer = parts[1];
                            String sendFile = parts[2];
                            String sendPeerKey = findPeerKey(sendPeer);
                            if (sendPeerKey != null) {
                                String[] sendParts = sendPeerKey.split(":");
                                sendFileWithConsent(sendParts[0], Integer.parseInt(sendParts[1]), sendPeer, sendFile);
                            } else {
                                System.out.println("Peer not found: " + sendPeer + ". Discovered peers: " + discoveredPeers.values());
                            }
                            break;

                        case "exit":
                            System.out.println("Shutting down...");
                            return;

                        default:
                            System.out.println("Unknown command. Use: list <peer>, request <peer> <filename>, send <peer> <filename>, exit");
                    }
                } else {
                    consentResponse = scanner.nextLine().trim().toLowerCase();
                }
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            if (jmdns != null) {
                try {
                    jmdns.unregisterAllServices();
                    jmdns.close();
                    System.out.println("JmDNS closed.");
                } catch (IOException e) {
                    System.out.println("Cleanup error: " + e.getMessage());
                }
            }
        }
    }

    private static String findPeerKey(String peerName) {
        for (Map.Entry<String, String> entry : discoveredPeers.entrySet()) {
            if (entry.getValue().startsWith(peerName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void runServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server listening on port " + PORT);
            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                     PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                     BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                     OutputStream binaryOut = clientSocket.getOutputStream()) {

                    String request = in.readLine();
                    if (request == null) continue;

                    if ("LIST_FILES".equals(request)) {
                        File dir = new File(sharedDir);
                        String[] files = dir.list();
                        if (files != null && files.length > 0) {
                            for (String file : files) {
                                out.println(file);
                            }
                        } else {
                            System.out.println("No files available in " + sharedDir);
                        }
                        out.println("END");
                        System.out.println("Sent file list to " + clientSocket.getInetAddress());
                    } else if (request.startsWith("REQUEST_FILE ")) {
                        String filename = request.substring("REQUEST_FILE ".length());
                        File file = new File(sharedDir, filename);
                        if (file.exists() && file.isFile()) {
                            awaitingConsent = true;
                            System.out.print("Peer " + clientSocket.getInetAddress() + 
                                             " requests " + filename + ". Approve? (y/n): ");
                            while (consentResponse == null && awaitingConsent) {
                                Thread.sleep(100);
                            }
                            String response = consentResponse;
                            consentResponse = null;
                            awaitingConsent = false;

                            if ("y".equals(response)) {
                                out.println("APPROVE " + filename);
                                out.flush();
                                try (FileInputStream fis = new FileInputStream(file)) {
                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    while ((bytesRead = fis.read(buffer)) != -1) {
                                        binaryOut.write(buffer, 0, bytesRead);
                                    }
                                    binaryOut.flush();
                                }
                                System.out.println("Sent file " + filename + " to " + clientSocket.getInetAddress());
                            } else {
                                out.println("DENY " + filename);
                                out.flush();
                                System.out.println("Denied file " + filename + " to " + clientSocket.getInetAddress());
                            }
                        } else {
                            out.println("DENY " + filename);
                            out.flush();
                            System.out.println("File " + filename + " not found");
                        }
                    } else if (request.startsWith("OFFER_FILE ")) {
                        String filename = request.substring("OFFER_FILE ".length());
                        awaitingConsent = true;
                        System.out.print("Peer " + clientSocket.getInetAddress() + 
                                         " offers " + filename + ". Accept? (y/n): ");
                        while (consentResponse == null && awaitingConsent) {
                            Thread.sleep(100);
                        }
                        String response = consentResponse;
                        consentResponse = null;
                        awaitingConsent = false;

                        if ("y".equals(response)) {
                            out.println("ACCEPT " + filename);
                            out.flush();
                            File file = new File(sharedDir, filename);
                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = clientSocket.getInputStream().read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                            System.out.println("Received file " + filename + " from " + clientSocket.getInetAddress());
                        } else {
                            out.println("DENY " + filename);
                            out.flush();
                            System.out.println("Denied file " + filename + " from " + clientSocket.getInetAddress());
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    System.out.println("Server error: " + e.getMessage());
                    awaitingConsent = false;
                    consentResponse = null;
                }
            }
        } catch (IOException e) {
            System.out.println("Could not start server: " + e.getMessage());
        }
    }

    private static void requestFile(String host, int port, String peerName, String filename) {
        String peerKey = host + ":" + port;
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             InputStream binaryIn = socket.getInputStream()) {

            out.println("REQUEST_FILE " + filename);
            System.out.println("Requesting file " + filename + " from " + peerName + " (" + peerKey + ")");
            String response = in.readLine();
            if (response != null && response.startsWith("APPROVE ")) {
                File file = new File(sharedDir, filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = binaryIn.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                System.out.println("Received file " + filename + " from " + peerName + " (" + peerKey + ")");
                System.out.println("Saved file to: " + file.getAbsolutePath());
            } else {
                System.out.println("Request for " + filename + " denied by " + peerName + " (" + peerKey + ")");
            }
        } catch (IOException e) {
            System.out.println("Error requesting file " + filename + " from " + peerName + " (" + peerKey + "): " + e.getMessage());
        }
    }

    private static void sendFileWithConsent(String host, int port, String peerName, String filename) {
        File file = new File(sharedDir, filename);
        if (!file.exists() || !file.isFile()) {
            System.out.println("File " + filename + " not found in " + sharedDir);
            return;
        }
        String peerKey = host + ":" + port;
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream binaryOut = socket.getOutputStream()) {

            out.println("OFFER_FILE " + filename);
            System.out.println("Offering file " + filename + " to " + peerName + " (" + peerKey + ")");
            String response = in.readLine();
            if (response != null && response.startsWith("ACCEPT ")) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        binaryOut.write(buffer, 0, bytesRead);
                    }
                    binaryOut.flush();
                }
                System.out.println("Sent file " + filename + " to " + peerName + " (" + peerKey + ")");
            } else {
                System.out.println("Offer for " + filename + " denied by " + peerName + " (" + peerKey + ")");
            }
        } catch (IOException e) {
            System.out.println("Error sending file " + filename + " to " + peerName + " (" + peerKey + "): " + e.getMessage());
        }
    }

    private static void requestFileList(String host, int port, String peerName) {
        String peerKey = host + ":" + port;
        try (Socket socket = new Socket(host, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("LIST_FILES");
            System.out.println("Requesting file list from " + peerName + " (" + peerKey + ")");
            List<String> fileList = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null && !line.equals("END")) {
                fileList.add(line);
            }
            peerFileLists.put(peerKey, fileList);
            if (fileList.isEmpty()) {
                System.out.println("No files available from " + peerName + " (" + peerKey + ")");
            } else {
                System.out.println("Files available from " + peerName + " (" + peerKey + "): " + String.join(", ", fileList));
            }
        } catch (IOException e) {
            System.out.println("Error requesting file list from " + peerName + " (" + peerKey + "): " + e.getMessage());
        }
    }

    private static class SampleListener implements ServiceListener {
        @Override
        public void serviceAdded(ServiceEvent event) {
            System.out.println("Service added: " + event.getName());
            event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1000);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            if (info != null) {
                InetAddress address = info.getAddress();
                if (address != null) {
                    String host = address.getHostAddress();
                    int port = info.getPort();
                    String peerKey = host + ":" + port;
                    discoveredPeers.remove(peerKey);
                    peerFileLists.remove(peerKey);
                    System.out.println("Service removed: " + info.getName());
                }
            }
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            if (info == null) {
                System.out.println("Service resolved but info is null: " + event.getName());
                return;
            }
            if ("JavaPeer".equals(info.getName())) {
                return;
            }

            System.out.println("Service resolved: " + info);
            InetAddress address = info.getAddress();
            if (address != null) {
                String host = address.getHostAddress();
                int port = info.getPort();
                String peerKey = host + ":" + port;
                discoveredPeers.put(peerKey, info.getName());
                System.out.println("Discovered peer: " + info.getName() + " at " + peerKey);
            } else {
                System.out.println("No address available for " + info.getName());
            }
        }
    }

    public static InetAddress getWifiIPv4Address() throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            try {
                if (ni.isUp() && !ni.isLoopback()) {
                    String displayName = ni.getDisplayName().toLowerCase();
                    if (displayName.contains("wi-fi") || displayName.contains("wlan") || displayName.contains("wireless")) {
                        Enumeration<InetAddress> addresses = ni.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress addr = addresses.nextElement();
                            if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                                System.out.println("Found Wi-Fi interface: " + ni.getDisplayName() + 
                                    " with IP: " + addr.getHostAddress());
                                return addr;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error checking interface " + ni.getDisplayName() + ": " + e.getMessage());
            }
        }
        System.out.println("No Wi-Fi interface detected, using fallback IP: 172.20.10.1");
        return InetAddress.getByName("172.20.10.1");
    }
}