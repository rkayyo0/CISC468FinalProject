# CISC468FinalProject
Associated code for CISC468 Final Project

We are submitting 4 files of code. 2 versions of the JavaPeer and 2 versions of the PythonPeer. We were unable to merge the functionality of them as there were issues we couldn't quite sort out. 
JavaPeer.java and python_peer.py have the discovery and registration fullt implemented, with a command line interface that allows for unsecure file transfer.
python_sts.py and JavaSTS.java have the station to station protocol implemented but not fully cohesive. 

Breakdown of the code in each file will be provided in the report. 

To operate JavaPeer,java and python_peer.py, 
1. run both files
2. wait for the prompt that says peer discovered
3. use any commands (case sensitive)

Commands Available:
list <peer_name>
send <peer_name> <file_name>
request <peer_name> <file_name>
exit 
