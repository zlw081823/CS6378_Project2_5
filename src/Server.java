import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class Server {
	final static int msgNum = 41;
	
	static BlockingQueue<Boolean> closeFlag = new ArrayBlockingQueue<Boolean>(10);
	static int terminateCnt = 0;
	
	public static void main(String[] args) {
		createFile("linwei.txt");
		
		try {
			ServerSocket serverSocket = new ServerSocket(6666);
			boolean listening = true;
			
			while (listening) {
				System.out.println("Waiting for socket to connect...");
				Socket clientSocket = serverSocket.accept();
				
				Thread serverSocketHandler = new Thread(new Runnable() {
					public void run() {
						String clientHostname = clientSocket.getInetAddress().getHostName();	// Get remote IP address
						int clientID = Integer.parseInt(clientHostname.split("\\.")[0].split("c")[1]);	// DC machine number
						
						try {
							ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
							ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
							
							Message msgIn;
							
							if ((msgIn = (Message) in.readObject()) != null) {
//								// First, operate on local disc
//								if (msgIn.getSeqNum() == 41) {
//									terminateCnt++;
//									if (terminateCnt == 7)
//										closeFlag.put(true);
//								} else {
//									writeFile("/tmp/user/java/linwei.txt", msgIn);				
//								}
//								out.writeObject(msgIn); 	// Just send something back as an indication of operation success!							
								
								if (clientID > 25) {	// Message from client - Broadcast it!
									forwardMessage(msgIn);
								}
								// Otherwise, just perform the operation and send some feedback
								if (msgIn.getSeqNum() == msgNum) {
									terminateCnt++;
									if (terminateCnt == 7) {
										if (clientID > 25) {	// This message comes from client - Send terminate to them!
											sendTerminate2Client();
										}
										closeFlag.put(true);
									}
								} else {
									writeFile("/tmp/user/java/linwei.txt", msgIn);	
									System.out.println("Write to file: <" + msgIn.getSenderID() + ", " + msgIn.getSeqNum() + ", " + msgIn.getSenderHostName() + ">");
								}
								out.writeObject(msgIn); 	// Just send something back as an indication of operation success!
							}
							
							out.close();
							in.close();
							clientSocket.close();
							System.out.println("Accepted socket closed!");
						} catch (IOException e) {
							e.printStackTrace();
						} catch (ClassNotFoundException e) {
							e.printStackTrace();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				});
				serverSocketHandler.start();
				serverSocketHandler.join();
				
				if (closeFlag.isEmpty() == false) {
					listening = false;
				}
			}
			
			serverSocket.close();
			System.out.println("Closing the server...");
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}  
	}
	
	public static void createFile (String fileName) {
		String fileDir = "/tmp/user/java/" + fileName;
		File file = new File(fileDir);
		
		if (file.exists()) {
			System.out.println("File <" + fileName + "> already exists!");
		} else {
			File parentFile = file.getParentFile();
			parentFile.mkdirs();
			try {
				file.createNewFile();
				System.out.println("File <" + fileName + "> is created!");
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Creating file failed!");
			}
		}		
	}
	
	public static void writeFile (String filePath, Message msg) {
		File file = new File(filePath);
		
		if (file.exists()) {
			try {
				FileWriter writer = new FileWriter(file, true);
				writer.write("< " + msg.getSenderID() + ", " + msg.getSeqNum() + ", " + msg.getSenderHostName() + " >");
				writer.write(System.getProperty("line.separator"));	// Insert new line!
				writer.close();				
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Cannot open the file writer!");
			}
		}	
	}
	
	private static void forwardMessage (Message msgIn) {	
		String serverHostname = null;
		try {
			serverHostname = Inet4Address.getLocalHost().getHostName();
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
		int serverID = Integer.parseInt(serverHostname.split("\\.")[0].split("c")[1]);	// DC machine number
		
		for (int sID = 23; sID < 26; sID ++) {
			if (sID != serverID) {
				try {
					Socket s2sSocket = new Socket(getHostname(sID), 6666);
					ObjectOutputStream out = new ObjectOutputStream(s2sSocket.getOutputStream());
					ObjectInputStream in = new ObjectInputStream(s2sSocket.getInputStream());
					
					Message msgS2SOut = msgIn;
					out.writeObject(msgS2SOut);
					
					if ((Message) in.readObject() != null) {	
						System.out.println("Forward write request to server <" + getHostname(sID) + "> --- succeed!");
					}
					in.close();
					out.close();
					s2sSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
					System.err.println("Cannot connect to server[" + sID + "]");
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
					System.err.println("Cannot read from server[" + sID + "]");
				}
			}
		}
	}
	
	public static String getHostname (int ID) {
		String hostname = null;
		
		switch (ID) {
		case 23:
			hostname = "dc23.utdallas.edu";
			break;
		case 24:
			hostname = "dc24.utdallas.edu";
			break;
		case 25:
			hostname = "dc25.utdallas.edu";
			break;
		default:
			break;
		}
		
		return hostname;
	}
	
	public static void sendTerminate2Client (){
		
		for(int clientID = 26; clientID < 33; clientID++) {
			try {
				Socket sendSocket = new Socket("dc" + clientID + ".utdallas.edu", 6666);
				ObjectOutputStream out = new ObjectOutputStream(sendSocket.getOutputStream());
				Message msgOut = new Message("terminate");
				out.writeObject(msgOut);
				out.close();
				sendSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}		
		}
	}
}

