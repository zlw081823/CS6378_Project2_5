import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ClientHandler {

	final static int UNLOCKED = 0;
	final static int LOCKED = 1;
	final static int[][] QUORUM = {{1, 2, 4}, {2, 3, 4, 7}, {1, 3, 7}, {4, 5, 6, 7}, {2, 3, 5, 6},
			{1, 3, 6}, {3, 4, 5, 7}};
	final static int msgNum = 41;
	
	final static int REQUEST = 0;
	final static int REPLY = 1;
	final static int RELEASE = 2;
	final static int INQUIRE = 3;
	final static int FAILED = 4;
	final static int YIELD = 5;

	public volatile PriorityBlockingQueue<Message> reqWaitingQ = new PriorityBlockingQueue<>(300);
	public volatile PriorityBlockingQueue<Message> inquireRecvQ = new PriorityBlockingQueue<>(100);	// inquires I received
	public volatile PriorityBlockingQueue<Message> inqWaitingQ = new PriorityBlockingQueue<>(100); // HP requests who are waiting for me to reply

	public volatile Message currentRequest = null;
	
	public volatile int replyCnt = 0;
	public volatile int state = UNLOCKED;
	public volatile int[] quorumRecvCnt = {0, 0, 0, 0, 0, 0, 0};	// indicate whether I q1 has received request from q2 & q2, to avoid unnecessary failed message
	public volatile boolean inquireSendFlg = false;
	public volatile boolean failedFlg = false;
	
	public volatile int[] msgSendCnt = {0, 0, 0, 0, 0, 0};
	public volatile int[] msgRecvCnt = {0, 0, 0, 0, 0, 0};
	public volatile int msgExCntTotal = 0;
	
	private Lock lock = new ReentrantLock();
	private Condition cond = lock.newCondition();
	
	/**
	 * This method is used to generate requests and put it in a priority queue
	 * @param clientID
	 * @throws InterruptedException 
	 */
	public void reqGenerate (int clientID) throws InterruptedException {
		String clientHostName = "dc" + (clientID + 25) + ".utdallas.edu";
		Random r = new Random();
		int seqNum  = 1;
		
		while (seqNum <= msgNum) {
			if (seqNum == 1) {
				Message request = new Message(clientID, clientHostName, seqNum, "request", System.currentTimeMillis());
				seqNum ++;
				
				// Generate and send at the same time!
				new Thread(new Runnable() {
					public void run() {
						for (int targetID : QUORUM[clientID - 1]) {	// Send the request to everyone in the QUORUM ( ID - 1) -> out of bound!
							try {
								Socket sendSocket = new Socket("dc" + (targetID + 25) + ".utdallas.edu", 6666);
								ObjectOutputStream out = new ObjectOutputStream(sendSocket.getOutputStream());
								out.writeObject(request);
								out.close();
								sendSocket.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				}).start();
				
				try {
					Thread.sleep(r.nextInt(50 - 10) + 10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} else {
				lock.lock();
//				System.out.println("Wait for the signal of leaving CS!");
//				long time = System.currentTimeMillis();
				cond.await();	// Hand over the lock
//				System.out.println("Time spend elapsed on waiting for CS leaving signal: <" + (System.currentTimeMillis() - time) + ">ms!!!");
				
				try {
					//time = System.currentTimeMillis();
					Message request = new Message(clientID, clientHostName, seqNum, "request", System.currentTimeMillis());
					//System.out.println("Time spend on GENERATING a REQUEST: <" + (System.currentTimeMillis() - time) + ">ms!!!");
					seqNum ++;
					
					// Generate and send at the same time!
					new Thread(new Runnable() {
						public void run() {
							long time = System.currentTimeMillis();
							for (int targetID : QUORUM[clientID - 1]) {	// Send the request to everyone in the QUORUM ( ID - 1) -> out of bound!
								try {
									Socket sendSocket = new Socket("dc" + (targetID + 25) + ".utdallas.edu", 6666);
									ObjectOutputStream out = new ObjectOutputStream(sendSocket.getOutputStream());
									out.writeObject(request);
									out.close();
									sendSocket.close();
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
							System.out.println("Time spend on SENDING a REQUEST to all clients in the quorum: <" + (System.currentTimeMillis() - time) + ">ms!!!");
						}
					}).start();
					
					try {
						Thread.sleep(r.nextInt(50 - 10) + 10);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}				
				} finally {
					lock.unlock();
				}				
			}
		}	
	}
	
	public void requestHandler (Message msgIn, int clientID) throws InterruptedException {
		// Routine count
		msgExCntTotal++;
		msgRecvCnt[REQUEST]++;
		
		if (state == UNLOCKED) {	// waitingQ is empty & current request is null
			state = LOCKED;
			currentRequest = msgIn;
			quorumRecvCnt[msgIn.getSenderID() - 1] ++;
			if (currentRequest.getSenderID() == clientID) {	// Don't send reply to myself, just replyCnt++
				replyCnt ++;
//				msgExCntTotal++;
//				msgRecvCnt[REPLY]++;	// Same as receiving a reply
				if (replyCnt == QUORUM[clientID - 1].length) {
					lock.lock();
					cond.signal();	// wake another awaiting lock up
					try {
						replyCnt = 0;
						failedFlg = false;	// Trust me, this is correct!
						System.out.println("Enter CRITICAL SECTION!!!!!!!");
						long time  = System.currentTimeMillis();
						logInfo(currentRequest.getSenderID(), System.currentTimeMillis() - currentRequest.getTimeStamp());
						//System.out.println("Time spend to log the info into file: <" + (System.currentTimeMillis() - time) + ">ms!!!!");
						time = System.currentTimeMillis();
						sendRequest2Server(currentRequest);
						System.out.println("Leave CRITICAL SECTION!!!!!!! Total time spent in CS: <" + (System.currentTimeMillis() - time) + ">ms!!!");
						time = System.currentTimeMillis();
						for (int targetID : QUORUM[clientID - 1]) {
							msgExCntTotal++;
							msgSendCnt[RELEASE]++;
							sendMsg2Client("release", targetID);
							System.out.println("<RELEASE> has been sent to client <" + targetID + "> in the QUORUM!!!!!!!!!!!!");
						}	
						inquireRecvQ.clear(); 	// Will this work?????
						System.out.println("Time spend on SENDING RELEASE to all clients in the quorum: <" + (System.currentTimeMillis() - time) + ">ms!!!");
					} finally {
						System.out.println("After sending RELEASE, current state<" + state + ">, replyCnt<" + replyCnt + ">, failedFlg<" +failedFlg + ">, inquireFlg<" + inquireSendFlg + ">...");
						System.out.println("REQUEST waiting queue<" + reqWaitingQ.size() + ">, INQUIRE waiting queue<" + inqWaitingQ.size() + ">...");
						System.out.println("Requests from quorum member: 1<" + quorumRecvCnt[0] + ">, 2<" + quorumRecvCnt[1] + ">, 3<" + quorumRecvCnt[2] + ">, 4<" + quorumRecvCnt[3] + ">, 5<" + quorumRecvCnt[4] + ">, 6<" + quorumRecvCnt[5] + ">, 7<" + quorumRecvCnt[6] + ">..,");
						lock.unlock();
					}
				}
			} else {
				msgExCntTotal++;
				msgSendCnt[REPLY]++;
				sendMsg2Client("reply", currentRequest.getSenderID());
				System.out.println("Send <REPLY> to client <" + currentRequest.getSenderID() + ">!!!!!!!!!!!!!!!!");
			}
		} else {	// already send reply to some client, including myself
			reqWaitingQ.add(msgIn);
			if (quorumRecvCnt[msgIn.getSenderID() - 1] == 0) { // no request sent from this same client before
				quorumRecvCnt[msgIn.getSenderID() - 1] ++;
				if (msgIn.compareTo(currentRequest) == -1 && msgIn.compareTo(reqWaitingQ.peek()) == 0) {	// HP than current request and the HIGEST in the waiting queue
					if (currentRequest.getSenderID() == clientID) {	// current request is myself! I need to handle INQUIRE from myself & handle YIELD from myself
						if (failedFlg == true) {	// Same as receiving a YIELD!
							reqWaitingQ.put(currentRequest);	// Put myself (LP) back in the Q
							currentRequest = reqWaitingQ.take();	// Take out HP one from the Q
							msgExCntTotal++;
							msgSendCnt[REPLY]++;
							sendMsg2Client("reply", currentRequest.getSenderID());
							System.out.println("Send <REPLY> to client <" + currentRequest.getSenderID() + ">");																		
						} else {
							inqWaitingQ.put(msgIn); 	// Store those coming request with HP than current request, only send reply to the top of them, and failed to the other 
						}
					} else {	// current request is other clients in my quorum!
						inqWaitingQ.put(msgIn); 	// Store those coming request with HP than current request, only send reply to the top of them, and failed to the other 
						if (inquireSendFlg == false) {
							msgExCntTotal++;
							msgSendCnt[INQUIRE]++;
							inquireSendFlg = true;
							sendMsg2Client("inquire", currentRequest.getSenderID());
							System.out.println("Send <INQUIRE> to client <" + currentRequest.getSenderID() + ">, current inquireSendFlg is <" + inquireSendFlg + ">");												
						} else {
							System.out.println("Already sent <INQUIRE> before!");
						}						
					}
					
				} else {
					msgExCntTotal++;
					msgSendCnt[FAILED]++;
					sendMsg2Client("failed", msgIn.getSenderID());
					System.out.println("Send <FAILED> to client <" + msgIn.getSenderID() + ">");
				}						
			} else {
				System.out.println("Previous <REQUEST> from the same client has not been processed or has not finished processing!");
			}
		}
	}

	public void replyHandler (int clientID) {
		// Routine count
		msgExCntTotal++;
		msgRecvCnt[REPLY]++;

		replyCnt ++;
		if (replyCnt == QUORUM[clientID - 1].length) {
			lock.lock();
			cond.signal();
			try {
				replyCnt = 0;
				failedFlg = false;	// Trust me, this is correct!
				System.out.println("Enter CRITICAL SECTION!!!!!!!");
				long time  = System.currentTimeMillis();
				logInfo(currentRequest.getSenderID(), System.currentTimeMillis() - currentRequest.getTimeStamp());
				//System.out.println("Time spend to log the info into file: <" + (System.currentTimeMillis() - time) + ">ms!!!!");
				time = System.currentTimeMillis();
				sendRequest2Server(currentRequest);
				System.out.println("Leave CRITICAL SECTION!!!!!!! Total time spent in CS: <" + (System.currentTimeMillis() - time) + ">ms!!!");
				for (int targetID : QUORUM[clientID - 1]) {
					msgExCntTotal++;
					msgSendCnt[RELEASE]++;
					sendMsg2Client("release", targetID);
					System.out.println("<RELEASE> has been sent to client <" + targetID + "> in the QUORUM!!!!!!!!!!!!");
				}				
				inquireRecvQ.clear();
			} finally {
				System.out.println("After sending RELEASE, current state<" + state + ">, replyCnt<" + replyCnt + ">, failedFlg<" +failedFlg + ">, inquireFlg<" + inquireSendFlg + ">...");
				System.out.println("REQUEST waiting queue<" + reqWaitingQ.size() + ">, INQUIRE waiting queue<" + inqWaitingQ.size() + ">...");
				System.out.println("Requests from quorum member: 1<" + quorumRecvCnt[0] + ">, 2<" + quorumRecvCnt[1] + ">, 3<" + quorumRecvCnt[2] + ">, 4<" + quorumRecvCnt[3] + ">, 5<" + quorumRecvCnt[4] + ">, 6<" + quorumRecvCnt[5] + ">, 7<" + quorumRecvCnt[6] + ">..,");
				lock.unlock();
			}
		}
	}
	
	public void failedHandler (int clientID) throws InterruptedException {
		// Routine count
		msgExCntTotal++;
		msgRecvCnt[FAILED]++;
		
		failedFlg = true;
		if (currentRequest.getSenderID() == clientID) {	// only send YIELD when I send reply to OTHER clients
			while (inquireRecvQ.isEmpty() == false) {
				msgExCntTotal++;
				msgSendCnt[YIELD]++;
				int targetID = inquireRecvQ.take().getSenderID();
				sendMsg2Client("yield", targetID);
				replyCnt --;
				System.out.println("Send postponed <YIELD> to client <" + targetID + "> due to receiving <FAILED>!");
			}			
		}
	}
	
	/**
	 * 
	 * @param msgIn (INQUIRE)
	 */
	public void inquireHandler (Message msgIn) {
		// Routine count
		msgExCntTotal++;
		msgRecvCnt[INQUIRE]++;

		if (failedFlg == true) {
			msgExCntTotal++;
			msgSendCnt[YIELD]++;
			sendMsg2Client("yield", msgIn.getSenderID());
			replyCnt --;
 			System.out.println("Send <YIELD> to client <" + msgIn.getSenderID() + "> due to existing FAILED received!");
		} else {
			inquireRecvQ.put(msgIn);	// All these are INQUIRE
		}
	}
	
	public void yieldHandler (int clientID) throws InterruptedException {
		// Routine count
		msgExCntTotal++;
		msgRecvCnt[YIELD]++;

		// Also need to send failed to those in the inquireSendQ
		inquireSendFlg = false;
		reqWaitingQ.put(currentRequest);
		currentRequest = reqWaitingQ.take();
		
		if (currentRequest.getSenderID() == clientID) {	// Don't send reply to myself, just replyCnt++
			replyCnt ++;
			if (replyCnt == QUORUM[clientID - 1].length) {
				lock.lock();
				cond.signal();
				try {
					replyCnt = 0;
					failedFlg = false;	// Trust me, this is correct!
					System.out.println("Enter CRITICAL SECTION!!!!!!!");
					long time  = System.currentTimeMillis();
					logInfo(currentRequest.getSenderID(), System.currentTimeMillis() - currentRequest.getTimeStamp());
					//System.out.println("Time spend to log the info into file: <" + (System.currentTimeMillis() - time) + ">ms!!!!");
					time = System.currentTimeMillis();
					sendRequest2Server(currentRequest);
					System.out.println("Leave CRITICAL SECTION!!!!!!! Total time spent in CS: <" + (System.currentTimeMillis() - time) + ">ms!!!");
					for (int targetID : QUORUM[clientID - 1]) {
						msgExCntTotal++;
						msgSendCnt[RELEASE]++;
						sendMsg2Client("release", targetID);
						System.out.println("<RELEASE> has been sent to client <" + targetID + "> in the QUORUM!!!!!!!!!!!!");
					}						
					inquireRecvQ.clear();
				} finally {
					System.out.println("After sending RELEASE, current state<" + state + ">, replyCnt<" + replyCnt + ">, failedFlg<" +failedFlg + ">, inquireFlg<" + inquireSendFlg + ">...");
					System.out.println("REQUEST waiting queue<" + reqWaitingQ.size() + ">, INQUIRE waiting queue<" + inqWaitingQ.size() + ">...");
					System.out.println("Requests from quorum member: 1<" + quorumRecvCnt[0] + ">, 2<" + quorumRecvCnt[1] + ">, 3<" + quorumRecvCnt[2] + ">, 4<" + quorumRecvCnt[3] + ">, 5<" + quorumRecvCnt[4] + ">, 6<" + quorumRecvCnt[5] + ">, 7<" + quorumRecvCnt[6] + ">..,");
					lock.unlock();
				}
			} else {
				if (failedFlg == true) {	// failed received when I already sent reply to other clients
					while (inquireRecvQ.isEmpty() == false) {
						msgExCntTotal++;
						msgSendCnt[YIELD]++;
						int targetID = inquireRecvQ.take().getSenderID();
						sendMsg2Client("yield", targetID);
						replyCnt --;
						System.out.println("Send postponed <YIELD> to client <" + targetID + "> due to receiving <FAILED>!");
					}			
				}
			}
		} else {
			msgExCntTotal++;
			msgSendCnt[REPLY]++;
			sendMsg2Client("reply", currentRequest.getSenderID());
		}
		System.out.println("Send <REPLY> to new HP client <" + currentRequest.getSenderID() + ">");
		inqWaitingQ.remove();
		while (inqWaitingQ.isEmpty() == false) {
			msgExCntTotal++;
			msgSendCnt[FAILED]++;
			sendMsg2Client("failed", inqWaitingQ.take().getSenderID());
			System.out.println("Send <FAILED> to LP client in the same inqWaitingQ of new current request");
		}
	}
	
	public void releaseHandler (int clientID) throws InterruptedException {
		// Routine Check
		msgExCntTotal++;
		msgRecvCnt[RELEASE]++;

		quorumRecvCnt[currentRequest.getSenderID() - 1] --;	// Indicate the finish of a previous REQUEST ( Before refresh current request)
		
		if (inquireSendFlg == true) {	// INQUIRE on behalf of other HP clients
			inquireSendFlg = false;
			inqWaitingQ.remove();
			while (inqWaitingQ.isEmpty() == false) {
				msgExCntTotal++;
				msgSendCnt[FAILED]++;
				sendMsg2Client("failed", inqWaitingQ.take().getSenderID());
				System.out.println("Send <FAILED> to LP client in the same inqWaitingQ of new current request");
			}
		}
		
		if (currentRequest.getSenderID() == clientID && inqWaitingQ.isEmpty() == false) {	// When I finished CS and 
			currentRequest = reqWaitingQ.take();
			inqWaitingQ.remove(currentRequest);
			msgExCntTotal++;
			msgSendCnt[REPLY]++;
			sendMsg2Client("reply", currentRequest.getSenderID());
			System.out.println("Send <REPLY> to new HP client <" + currentRequest.getSenderID() + ">");
			while (inqWaitingQ.isEmpty() == false) {
				msgExCntTotal++;
				msgSendCnt[FAILED]++;
				sendMsg2Client("failed", inqWaitingQ.take().getSenderID());
				System.out.println("Send <FAILED> to LP client in the same inqWaitingQ of new current request");				 
			}
		} else {
			if (reqWaitingQ.isEmpty()) {
				currentRequest = null;
				state = UNLOCKED;
				System.out.println("No more waiting requests! Current state is changed to <" + state + ">!!!!!!!!!!!!!!!!!!!!!!s");
			} else {
				currentRequest = reqWaitingQ.take();
				state = LOCKED;	// Unchanged
				if (currentRequest.getSenderID() == clientID) {	// Don't send reply to myself, just replyCnt++
//					msgExCntTotal++;
//					msgRecvCnt[REPLY]++;
					replyCnt ++;
					if (replyCnt == QUORUM[clientID - 1].length) {
						lock.lock();
						cond.signal();
						try {
							replyCnt = 0;
							failedFlg = false;	// Trust me, this is correct!
							System.out.println("Enter CRITICAL SECTION!!!!!!!");
							long time  = System.currentTimeMillis();
							logInfo(currentRequest.getSenderID(), System.currentTimeMillis() - currentRequest.getTimeStamp());
							//System.out.println("Time spend to log the info into file: <" + (System.currentTimeMillis() - time) + ">ms!!!!");
							time = System.currentTimeMillis();
							sendRequest2Server(currentRequest);
							System.out.println("Leave CRITICAL SECTION!!!!!!! Total time spent in CS: <" + (System.currentTimeMillis() - time) + ">ms!!!");
							for (int targetID : QUORUM[clientID - 1]) {
								msgExCntTotal++;
								msgSendCnt[RELEASE]++;
								sendMsg2Client("release", targetID);
								System.out.println("<RELEASE> has been sent to client <" + targetID + "> in the QUORUM!!!!!!!!!!!!");
							}						
							inquireRecvQ.clear();
						} finally {
							System.out.println("After sending RELEASE, current state<" + state + ">, replyCnt<" + replyCnt + ">, failedFlg<" +failedFlg + ">, inquireFlg<" + inquireSendFlg + ">...");
							System.out.println("REQUEST waiting queue<" + reqWaitingQ.size() + ">, INQUIRE waiting queue<" + inqWaitingQ.size() + ">...");
							System.out.println("Requests from quorum member: 1<" + quorumRecvCnt[0] + ">, 2<" + quorumRecvCnt[1] + ">, 3<" + quorumRecvCnt[2] + ">, 4<" + quorumRecvCnt[3] + ">, 5<" + quorumRecvCnt[4] + ">, 6<" + quorumRecvCnt[5] + ">, 7<" + quorumRecvCnt[6] + ">..,");
							lock.unlock();
						}
					} else {
						if (failedFlg == true) {	// failed received when I already sent reply to other clients
							while (inquireRecvQ.isEmpty() == false) {
								msgExCntTotal++;
								msgSendCnt[YIELD]++;
								int targetID = inquireRecvQ.take().getSenderID();
								sendMsg2Client("yield", targetID);
								replyCnt --;
								System.out.println("Send postponed <YIELD> to client <" + targetID + "> due to receiving <FAILED>!");
							}			
						}
					}
				} else {
					msgExCntTotal++;
					msgSendCnt[REPLY]++;
					sendMsg2Client("reply", currentRequest.getSenderID());
				}
				System.out.println("Send reply to new top currentRequest <" + currentRequest.getSenderID() + ">");
			}
		}
		
	}
	
	/**
	 * This method is used to send message to clients (only send)
	 * @param msgType
	 * @param targetClientID
	 */
	public static void sendMsg2Client (String msgType, int targetClientID) {
		
		try {
			Socket sendSocket = new Socket("dc" + (targetClientID + 25) + ".utdallas.edu", 6666);	// All ID are from 1 to 7!!
			ObjectOutputStream out = new ObjectOutputStream(sendSocket.getOutputStream());
			Message msgOut = new Message(msgType);	// When send inquire I need to reply? Have I handle that already?
			out.writeObject(msgOut);
			out.close();
			sendSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
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
	
	public void createFile (int clientID) {
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
	
	public void write1Line2File (int clientID, String log) {
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
	
	public void logInfo (int clientID, long timeElapse) {
		String numOfMsgSend = "SEND - Request <" + msgSendCnt[REQUEST] + ">, " + "Reply <" + msgSendCnt[REPLY] + ">, " + "Release <" + msgSendCnt[RELEASE] + ">, " + "Inquire <" + msgSendCnt[INQUIRE] + ">, " + "Failed <" + msgSendCnt[FAILED] + ">, " + "Yield <" + msgSendCnt[YIELD] + ">. ";
		String numOfMsgRecv = "RECV - Request <" + msgRecvCnt[REQUEST] + ">, " + "Reply <" + msgRecvCnt[REPLY] + ">, " + "Release <" + msgRecvCnt[RELEASE] + ">, " + "Inquire <" + msgRecvCnt[INQUIRE] + ">, " + "Failed <" + msgRecvCnt[FAILED] + ">, " + "Yield <" + msgRecvCnt[YIELD] + ">. ";
		String time = "Time elapsed is <" + timeElapse + ">";
		for (int i = 0; i < 6; i++) {
			msgSendCnt[i] = 0;
			msgRecvCnt[i] = 0;
		}
		write1Line2File(clientID, numOfMsgSend);
		write1Line2File(clientID, numOfMsgRecv);
		write1Line2File(clientID, time);
		write1Line2File(clientID, "----------------------------------------------------------------------------------------------------------");
	}
	
	public int getTotalExMsg () {
		return msgExCntTotal;
	}
}
