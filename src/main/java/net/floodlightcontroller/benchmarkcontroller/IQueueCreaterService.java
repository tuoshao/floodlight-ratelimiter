package net.floodlightcontroller.benchmarkcontroller;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.IFloodlightService;

public interface IQueueCreaterService extends IFloodlightService {

	/**
	 * Get queue configurations from one switch
	 * @param sw
	 */
    public void getAllQueueConfigs(IOFSwitch sw);
    
    /**
     * Create a queue on a switch
     * @param sw
     * @param portNumber
     * @param queueId
     * @param rate
     */
    public void createQueue(IOFSwitch sw, short portNumber, int queueId, short rate);
    
    /**
     * Delete a queue on a switch
     * @param sw
     * @param portNumber
     * @param queueId
     */
    public void deleteQueue(IOFSwitch sw, short portNumber, int queueId);
    

}
