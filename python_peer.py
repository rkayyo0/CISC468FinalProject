from zeroconf import Zeroconf, ServiceInfo, ServiceBrowser, ServiceListener
import socket
import threading
import os

class PeerNode:
    def __init__(self, service_name="PythonPeer", port=5000, shared_dir=r"C:\Users\ryank\OneDrive\Documents\CISC 468\share_p2p_python"):
        self.service_name = f"{service_name}._p2pfile._tcp.local."
        self.port = port
        self.shared_dir = shared_dir
        self.zeroconf = Zeroconf()
        self.local_ip = socket.gethostbyname(socket.gethostname())
        self.discovered_peers = {}
        self.peer_file_lists = {}

        if not os.path.exists(shared_dir):
            os.makedirs(shared_dir)
        print(f"Using shared directory: {os.path.abspath(shared_dir)}")

        self.register_service()

        self.server_thread = threading.Thread(target=self.start_server, daemon=True)
        self.server_thread.start()

        self.browser = ServiceBrowser(self.zeroconf, "_p2pfile._tcp.local.", self)

        print(f"Python peer started on {self.local_ip}")

        self.run_cli()

    def register_service(self):
        info = ServiceInfo(
            "_p2pfile._tcp.local.",
            self.service_name,
            addresses=[socket.inet_aton(self.local_ip)],
            port=self.port,
            properties={"desc": "Secure File Sharing Peer"},
        )
        self.zeroconf.register_service(info)
        print(f"Registered {self.service_name} at {self.local_ip}:{self.port}")

    def start_server(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind(("", self.port))
        server.listen(5)
        print(f"Server listening on port {self.port}")

        while True:
            client, addr = server.accept()
            with client:
                try:
                    data = client.recv(1024).decode().strip()
                    if data == "LIST_FILES":
                        files = os.listdir(self.shared_dir)
                        if files:
                            for file in files:
                                client.send(f"{file}\n".encode())
                        else:
                            print(f"No files available in {self.shared_dir}")
                        client.send("END\n".encode())
                        print(f"Sent file list to {addr}")
                    elif data.startswith("REQUEST_FILE "):
                        filename = data[len("REQUEST_FILE "):]
                        file_path = os.path.join(self.shared_dir, filename)
                        if os.path.isfile(file_path):
                            response = input(f"Peer {addr[0]}:{addr[1]} requests {filename}. Approve? (y/n): ").strip().lower()
                            if response == "y":
                                client.send(f"APPROVE {filename}\n".encode())
                                with open(file_path, "rb") as f:
                                    while True:
                                        bytes_read = f.read(4096)
                                        if not bytes_read:
                                            break
                                        client.sendall(bytes_read)
                                print(f"Sent file {filename} to {addr}")
                            else:
                                client.send(f"DENY {filename}\n".encode())
                                print(f"Denied file {filename} to {addr}")
                        else:
                            client.send(f"DENY {filename}\n".encode())
                            print(f"File {filename} not found")
                    elif data.startswith("OFFER_FILE "):
                        filename = data[len("OFFER_FILE "):]
                        response = input(f"Peer {addr[0]}:{addr[1]} offers {filename}. Accept? (y/n): ").strip().lower()
                        if response == "y":
                            client.send(f"ACCEPT {filename}\n".encode())
                            file_path = os.path.join(self.shared_dir, filename)
                            with open(file_path, "wb") as f:
                                while True:
                                    data = client.recv(4096)
                                    if not data:
                                        break
                                    f.write(data)
                            print(f"Received file {filename} from {addr}")
                        else:
                            client.send(f"DENY {filename}\n".encode())
                            print(f"Denied file {filename} to {addr}")
                    client.shutdown(socket.SHUT_WR)
                except Exception as e:
                    print(f"Server error handling client {addr}: {e}")

    def add_service(self, zeroconf, service_type, name):
        info = zeroconf.get_service_info(service_type, name)
        if info and name != self.service_name:
            ip = socket.inet_ntoa(info.addresses[0])
            port = info.port
            peer_key = f"{ip}:{port}"
            self.discovered_peers[peer_key] = name
            print(f"Discovered peer: {name} at {peer_key}")

    def remove_service(self, zeroconf, service_type, name):
        for peer_key, peer_name in list(self.discovered_peers.items()):
            if peer_name == name:
                del self.discovered_peers[peer_key]
                self.peer_file_lists.pop(peer_key, None)
                print(f"Service removed: {name}")

    def request_file(self, host, port, peer_name, filename):
        peer_key = f"{host}:{port}"
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(30)
                s.connect((host, port))
                s.send(f"REQUEST_FILE {filename}\n".encode())
                print(f"Requesting file {filename} from {peer_name} ({peer_key})")
                response = s.recv(1024).decode().strip()
                if response.startswith("APPROVE "):
                    file_path = os.path.join(self.shared_dir, filename)
                    with open(file_path, "wb") as f:
                        while True:
                            data = s.recv(4096)
                            if not data:
                                break
                            f.write(data)
                    print(f"Received file {filename} from {peer_name} ({peer_key})")
                    print(f"Saved file to: {file_path}")
                else:
                    print(f"Request for {filename} denied by {peer_name} ({peer_key})")
        except Exception as e:
            print(f"Error requesting file {filename} from {peer_name} ({peer_key}): {e}")

    def send_file(self, host, port, peer_name, filename):
        file_path = os.path.join(self.shared_dir, filename)
        if not os.path.isfile(file_path):
            print(f"File {filename} not found in {self.shared_dir}")
            return
        peer_key = f"{host}:{port}"
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(30)
                s.connect((host, port))
                s.send(f"OFFER_FILE {filename}\n".encode())
                print(f"Offering file {filename} to {peer_name} ({peer_key})")
                response = s.recv(1024).decode().strip()
                if response.startswith("ACCEPT "):
                    with open(file_path, "rb") as f:
                        while True:
                            bytes_read = f.read(4096)
                            if not bytes_read:
                                break
                            s.sendall(bytes_read)
                    print(f"Sent file {filename} to {peer_name} ({peer_key})")
                else:
                    print(f"Offer for {filename} denied by {peer_name} ({peer_key})")
        except Exception as e:
            print(f"Error sending file {filename} to {peer_name} ({peer_key}): {e}")

    def request_file_list(self, host, port, peer_name):
        peer_key = f"{host}:{port}"
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(30)
                s.connect((host, port))
                s.send("LIST_FILES\n".encode())
                print(f"Requesting file list from {peer_name} ({peer_key})")
                file_list = []
                while True:
                    line = s.recv(1024).decode().strip()
                    if not line or line == "END":
                        break
                    file_list.append(line)
                self.peer_file_lists[peer_key] = file_list
                if not file_list:
                    print(f"No files available from {peer_name} ({peer_key})")
                else:
                    print(f"Files available from {peer_name} ({peer_key}): {', '.join(file_list)}")
        except Exception as e:
            print(f"Error requesting file list from {peer_name} ({peer_key}): {e}")

    def find_peer_key(self, peer_name):
        for key, name in self.discovered_peers.items():
            if peer_name in name:
                return key
        return None

    def run_cli(self):
        print("Commands: list <peer>, request <peer> <filename>, send <peer> <filename>, exit")
        while True:
            try:
                command = input("Enter command: ").strip()
                parts = command.split()

                if not parts:
                    continue

                if parts[0].lower() == "list":
                    if len(parts) != 2:
                        print("Usage: list <peer>")
                        continue
                    peer_name = parts[1]
                    peer_key = self.find_peer_key(peer_name)
                    if peer_key:
                        ip, port = peer_key.split(":")
                        self.request_file_list(ip, int(port), peer_name)
                    else:
                        print(f"Peer not found: {peer_name}. Discovered peers: {list(self.discovered_peers.values())}")

                elif parts[0].lower() == "request":
                    if len(parts) != 3:
                        print("Usage: request <peer> <filename>")
                        continue
                    peer_name, filename = parts[1], parts[2]
                    peer_key = self.find_peer_key(peer_name)
                    if peer_key:
                        ip, port = peer_key.split(":")
                        self.request_file(ip, int(port), peer_name, filename)
                    else:
                        print(f"Peer not found: {peer_name}. Discovered peers: {list(self.discovered_peers.values())}")

                elif parts[0].lower() == "send":
                    if len(parts) != 3:
                        print("Usage: send <peer> <filename>")
                        continue
                    peer_name, filename = parts[1], parts[2]
                    peer_key = self.find_peer_key(peer_name)
                    if peer_key:
                        ip, port = peer_key.split(":")
                        self.send_file(ip, int(port), peer_name, filename)
                    else:
                        print(f"Peer not found: {peer_name}. Discovered peers: {list(self.discovered_peers.values())}")

                elif parts[0].lower() == "exit":
                    print("Shutting down...")
                    self.close()
                    return

                else:
                    print("Unknown command. Use: list <peer>, request <peer> <filename>, send <peer> <filename>, exit")
            except Exception as e:
                print(f"CLI error: {e}")

    def close(self):
        self.zeroconf.unregister_all_services()
        self.zeroconf.close()

if __name__ == "__main__":
    import sys
    shared_dir = r"C:\Users\ryank\OneDrive\Documents\CISC 468\share_p2p_python" if len(sys.argv) < 2 else sys.argv[1]
    peer = PeerNode(shared_dir=shared_dir)