import java.io.Serializable;
import java.net.Inet4Address;
import java.net.UnknownHostException;

public class Message implements Serializable, Comparable<Message>{

	private static final long serialVersionUID = 1L;

	private int senderID;
	private String senderHostName;
	private int seqNum;
	private String msgType;
	private long timeStamp;
	
	/**
	 * Specially used to construct REQUEST message
	 * @param senderID
	 * @param senderHostName
	 * @param seqNum
	 * @param msgType
	 * @param timeStamp
	 */
	public Message(int senderID, String senderHostName, int seqNum, String msgType, long timeStamp) {
		// TODO Auto-generated constructor stub
		this.senderID = senderID;
		this.senderHostName = senderHostName;
		this.seqNum = seqNum;
		this.msgType = msgType;
		this.timeStamp = timeStamp;
	}
	
	/**
	 * Message with all initial value equals 0 or null
	 */
	public Message() {
		this.senderID = 0;
		this.senderHostName = null;
		this.seqNum = 0;
		this.msgType = null;
		this.timeStamp = 0;	
	}
	
	/**
	 * Message with msgType, senderHostName and senderID
	 * @param msgType
	 */
	public Message(String msgType) {
		// For the sack of easy debug
		try {
			this.senderHostName = Inet4Address.getLocalHost().getHostName();
			this.senderID = Integer.parseInt(senderHostName.split("\\.")[0].split("c")[1]) - 25;
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
//		this.senderID = 0;
//		this.senderHostName = null;
		this.seqNum = 0;
		this.msgType = msgType;
		this.timeStamp = 0;
	}
	
	public int getSenderID () {
		return senderID;
	}
	
	public String getSenderHostName () {
		return senderHostName;
	}
	
	public int getSeqNum () {
		return seqNum;
	}
	
	public String getMsgType () {
		return msgType;
	}
	
	public long getTimeStamp () {
		return timeStamp;
	}

	@Override
	public int compareTo(Message request) {
		// TODO Auto-generated method stub
		if (this.equals(request)) {
			return 0;
		} else if (this.getTimeStamp() < request.getTimeStamp()) {
			return -1;
		} else if (this.getTimeStamp() == request.getTimeStamp() && this.getSenderID() < request.senderID){
			return -1;
		} else {
			return 1;
		}
	}
}
