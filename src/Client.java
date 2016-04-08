import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

public class Client {

	public static volatile boolean listeningFlg = true;
	public static volatile boolean shutdownRequest = false;
	
	public static void main(String[] args) {
		// Get clientID first
		String clientHostname = null;
		try {
			clientHostname = Inet4Address.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		final int clientID = Integer.parseInt(clientHostname.split("\\.")[0].split("c")[1]) - 25; // 1 - 7

		// Create a local file to keep track of message exchange information and time elapse
		createFile(clientID);
		System.out.println("Log file <client" + clientID + "> is created!");
		
		// Initiate the clients		
		setupInitiator(clientID);	// 1 - 7
		System.out.println("Setup finished...");	

		// Start to listen to incoming sockets
		try {
			ServerSocket listenSocket = new ServerSocket(6666);
			boolean listening = true;
						
			Thread terminateThread = new Thread(new Runnable() {
				public void run() {
					while (listeningFlg);	
					try {
						Thread.sleep(1000);
						//listenSocket.close();
						shutdownRequest = true;
						new Socket("dc" + clientID + "utdallas.edu", 6666).close();
					} catch (IOException e) {
						System.out.println("Client <" + clientID + "> is closed!");						
					} catch (InterruptedException e) {
						e.printStackTrace();
					} 
//					finally {
//						System.out.println("Client <" + clientID + "> is closed!");	
//					}
				}
			});
			terminateThread.start();
					
			ClientHandler clientHandler = new ClientHandler();	// For synchronization
			System.out.println("New handler is created!");
			
			Thread reqGenerateThread = new Thread(new Runnable() {
				public void run() {
					try {
						Thread.sleep(100);
						clientHandler.reqGenerate(clientID);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			});
			reqGenerateThread.start();
			
			while (listening) {
				Socket inSocket = listenSocket.accept();
				
				if (shutdownRequest == false) {
					Thread inSocketHandlerThread = new Thread(new Runnable() {
						public void run() {
							try {
								long time = System.currentTimeMillis();
								ObjectInputStream in = new ObjectInputStream(inSocket.getInputStream());
								Message msgIn = (Message) in.readObject();
								System.out.println("Receive a message: <" + msgIn.getMsgType() + ">, TS <" + msgIn.getTimeStamp() + ">, from client <" + msgIn.getSenderID() + ">");
								in.close();
								inSocket.close();
								System.out.println("Time spend on setting up a socket is: <" + (System.currentTimeMillis() - time) + ">ms!!!");
								
								if (msgIn.getMsgType().equals("request")) {
									clientHandler.requestHandler(msgIn, clientID);
								} else if (msgIn.getMsgType().equals("reply")) {
									clientHandler.replyHandler(clientID);
								} else if (msgIn.getMsgType().equals("failed")) {
									clientHandler.failedHandler(clientID);
								} else if (msgIn.getMsgType().equals("inquire")) {	// From those who send you REPLY
									clientHandler.inquireHandler(msgIn);
								} else if (msgIn.getMsgType().equals("yield")) {
									clientHandler.yieldHandler(clientID);
								} else if (msgIn.getMsgType().equals("release")) {
									clientHandler.releaseHandler(clientID);
								} else if (msgIn.getMsgType().equals("terminate")) {
									System.out.println("Receive <terminate> from server!!!!!!!!");
									write1Line2File(clientID, "Total # of message exchanged <" + clientHandler.msgExCntTotal + ">");
									Thread.sleep(100);
									listeningFlg = false;
								}
								
							} catch (IOException e) {
								e.printStackTrace();
							} catch (ClassNotFoundException e) {
								e.printStackTrace();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}	
						}
					});
					inSocketHandlerThread.start();					
				} else {
					break;
				}
			}
			listenSocket.close();
			System.out.println("Client <" + clientID + "> is closed!");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * This method is used to send confirmation to previous set clients, and receive
	 * confirmation from later set clients
	 * @param clientID (1~7)
	 */
	public static int setupInitiator(int clientID) {

		int sendCnt = 1;
		while (sendCnt < clientID) {
			String serverHostname = "dc" + (25 + sendCnt) + ".utdallas.edu";
			try {
				Socket sendSocket  = new Socket(serverHostname, 6666);
				ObjectOutputStream out = new ObjectOutputStream(sendSocket.getOutputStream());
				ObjectInputStream in = new ObjectInputStream(sendSocket.getInputStream());
				out.writeObject(new Message());
				if(in.readObject() != null) {
					System.out.println("Send to synchronize client<" + sendCnt + "> ... Ready");
					sendCnt ++;
				}
				
				out.close();
				in.close();
				sendSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}				
		}
		
		int recvCnt = 0;
		try {
			ServerSocket c1Socket = new ServerSocket(6666);
			while (recvCnt < 7 - clientID) {
				Socket acceptedSocket = c1Socket.accept();
				ObjectOutputStream out = new ObjectOutputStream(acceptedSocket.getOutputStream());
				ObjectInputStream in = new ObjectInputStream(acceptedSocket.getInputStream());
				if (in.readObject() != null) {	// Just read sth to indicate it is set up
					System.out.println("Received initiate from <" + acceptedSocket.getRemoteSocketAddress() + ">");
					recvCnt++;
				}
				out.writeObject(new Message());
				out.close();
				in.close();
				acceptedSocket.close();
			}
			c1Socket.close();		
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		
		return clientID;
	}

	/**
	 * This method is used to send request to server during EXECUTING state
	 * @param currentRequest
	 */
	public synchronized static void sendRequest2Server (Message currentRequest) {
		String targetHostname = "dc" + ThreadLocalRandom.current().nextInt(23, 26) + ".utdallas.edu";	// Randomly choose a server
		try {
			Socket clientSocket = new Socket(targetHostname, 6666);
			ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
			ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
			
			Message msgOut = currentRequest;
			out.writeObject(msgOut);
			System.out.println("#" + currentRequest.getSeqNum() + " WRITE request sent!");
			
			if ((Message) in.readObject() != null) {	
				System.out.println("Write operation succeeded! - " + targetHostname);
			}
			
			out.close();
			in.close();
			clientSocket.close();		
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}		
	}

	public static void createFile (int clientID) {
		String fileDir = "/tmp/user/java/client" + clientID;
		File file = new File(fileDir);
		
		if (file.exists()) {
			System.out.println("File <client" + clientID + "> already exists!");
		} else {
			File parentFile = file.getParentFile();
			parentFile.mkdirs();
			try {
				file.createNewFile();
				System.out.println("File <client" + clientID + "> is created!");
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Creating file failed!");
			}
		}		
	}
	
	public static void write1Line2File (int clientID, String log) {
		File file = new File("/tmp/user/java/client" + clientID);
		
		if (file.exists()) {
			try {
				FileWriter writer = new FileWriter(file, true);
				writer.write(log);
				writer.write(System.getProperty("line.separator"));	// Insert new line!
				writer.close();				
			} catch (IOException e) {
				e.printStackTrace();
				System.err.println("Cannot open the file writer!");
			}
		}	
	}
	
}
