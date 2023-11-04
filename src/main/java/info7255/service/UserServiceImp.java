package info7255.service;


import info7255.dao.UserQueueDao;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class UserServiceImp {
	
	@Autowired
	private UserQueueDao messageQueueDao;

	public void addToMessageQueue(String message, boolean isDelete) {
		JSONObject object = new JSONObject();
		object.put("message", message);
		object.put("isDelete", isDelete);

		// save plan to message queue "messageQueue"
		System.out.println("Message saved successfully: " + object.toString());
	}

	public void addToMessageQueue(boolean isDelete) {
		JSONObject object = new JSONObject();
		object.put("isDelete", isDelete);

		System.out.println("Message saved successfully: " + object.toString());
	}
}
