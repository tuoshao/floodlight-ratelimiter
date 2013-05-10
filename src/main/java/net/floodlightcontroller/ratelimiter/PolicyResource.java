package net.floodlightcontroller.ratelimiter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import net.floodlightcontroller.packet.IPv4;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.openflow.protocol.OFMatch;
import org.openflow.util.U16;
import org.restlet.resource.Delete;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PolicyResource extends ServerResource {
public static Logger logger = LoggerFactory.getLogger(PolicyResource.class);
	
	
	@Get("json")
	public Object handleRequest(){
		RateLimiterService rs = 
                (RateLimiterService)getContext().getAttributes().
                get(RateLimiterService.class.getCanonicalName());
		
	    return rs.getPolicies();
	}
	
    @Post
    public String add(String policyJson) {
    	RateLimiterService rs = 
                (RateLimiterService)getContext().getAttributes().
                get(RateLimiterService.class.getCanonicalName());
    	
    	//dummy policy
    	Policy policy;
    	try{
    		policy = jsonToPolicy(policyJson);
    	}
    	catch(IOException e){
    		logger.error("Error Parsing Quality of Service Policy to JSON: {}, Error: {}", policyJson, e);
    		e.printStackTrace();
    		return "{\"status\" : \"Error! Could not parse policy, see log for details.\"}";
    	}
    	String status = null;
    	if(rs.checkIfPolicyExists(policy)){
    		status = "Error! This policy already exists!";
    		logger.error(status);
    	}
    	else{
			status = "Adding Policy: " + policy.toString();//add service
			rs.addPolicy(policy);
    			
    	}
    	return ("{\"status\" : \"" + status + "\"}");
    }
    
    /**
     * Deletes a policy
     * @param qosJson
     * @return status
     **/
    @Delete
    public String delete(String qosJson) {
    	RateLimiterService rs = 
                (RateLimiterService)getContext().getAttributes().
                get(RateLimiterService.class.getCanonicalName());
    	
    	Policy policy;
    	
    	try{
    		policy = jsonToPolicy(qosJson);
    	}
    	catch(IOException e){
    		logger.debug("Error Parsing QoS Policy to JSON: {}, Error: {}", qosJson, e);
    		e.printStackTrace();
    		return "{\"status\" : \"Error! Could not parse policy, see log for details.\"}";
    	}
    	String status = null;
    	if(!rs.checkIfPolicyExists(policy)){
    		status = "Error! This policy doesn't exist!";
    		logger.error(status);
    	}else{
    		rs.deletePolicy(policy);
    		status = "Type Of Service Service-ID: "+policy.policyid+" Deleted";
    	}
		
 
		return ("{\"status\" : \"" + status + "\"}");
    }
    
    /**
     * Turns POST json data into policy
     * @param pJson
     * @return
     * @throws IOException
     */
    public static Policy jsonToPolicy(String pJson) throws IOException{
		//initialize needs json tools
		MappingJsonFactory jf = new MappingJsonFactory();
		JsonParser jp;
		Set<OFMatch> rules = new HashSet<OFMatch>();
		short speed = 1000;
		
		try{
			jp = jf.createJsonParser(pJson);
		}catch(JsonParseException e){
			throw new IOException(e);
		}
		
		
    	JsonToken tkn = jp.getCurrentToken();
    	if(tkn != JsonToken.START_OBJECT){
    		jp.nextToken();
    		if(jp.getCurrentToken() != JsonToken.START_OBJECT){
    			logger.error("Did not recieve json start token, current " +
    					"token is: {}",jp.getCurrentToken());
    		}
    	}
    	while(jp.nextToken() != JsonToken.END_OBJECT){
    		if(jp.getCurrentToken() != JsonToken.FIELD_NAME){
    			throw new IOException("FIELD_NAME expected");
    		}
    		
    		try{
    			String tmpS = jp.getCurrentName();
    			tkn = jp.nextToken();
    			
    			/** may be worth: jsonText = jp.getText(); to avoid over
    			 *  use of jp.getText() method call **/
    			
    			//get current text of the FIELD_NAME
    			logger.info("Current text is "+ jp.getText()); //debug for dev
    			if(jp.getText().equals("")){
    				//back to beginning of loop
    				continue;
    			}
    			else if(tmpS == "rules") {
    				if(tkn == JsonToken.START_ARRAY) {
    					while(jp.nextToken() != JsonToken.END_ARRAY){
    						OFMatch rule = new OFMatch();
    						int wildcard = OFMatch.OFPFW_ALL;
    						rule.setInputPort((short)0);
    						JsonNode node = jp.readValueAsTree();
    						JsonNode field = node.get("vlan-id");
    						if(field != null){
    							rule.setDataLayerVirtualLan((short)field.getValueAsInt());
    							wildcard = wildcard & (~OFMatch.OFPFW_DL_VLAN);
    						}
    						field = node.get("eth-src");
    						if(field != null){
    							rule.setDataLayerSource(field.getValueAsText());
    							wildcard = wildcard & (~OFMatch.OFPFW_DL_SRC);
    						}
    						field = node.get("eth-dst");
    						if(field != null){
    							rule.setDataLayerDestination(field.getValueAsText());
    							wildcard = wildcard & (~OFMatch.OFPFW_DL_DST);
    						}
    						field = node.get("eth-type");
    						if(field != null){
    							if (field.getValueAsText().startsWith("0x")) {
    								rule.setDataLayerType(U16.t(Integer.valueOf
    										(jp.getText().replaceFirst("0x",""),16)));
    							}
    							else{rule.setDataLayerType((short) Integer.parseInt(jp.getText()));}
    							wildcard = wildcard & (~OFMatch.OFPFW_DL_TYPE);
    						}
    						field = node.get("protocol");
    						if(field != null){
    							rule.setNetworkProtocol((byte)field.getValueAsInt());
    							wildcard = wildcard & (~OFMatch.OFPFW_NW_PROTO);
    						}
    						field = node.get("port-src");
    						if(field != null){
    							rule.setTransportSource((short)field.getValueAsInt());
    							wildcard = wildcard & (~OFMatch.OFPFW_TP_SRC);
    						}
    						field = node.get("port-dst");
    						if(field != null){
    							rule.setTransportDestination((short)field.getValueAsInt());
    							wildcard = wildcard & (~OFMatch.OFPFW_TP_DST);
    						}
    						field = node.get("ip-src");
    						if(field != null){
    							rule.setNetworkSource(IPv4.toIPv4Address(field.getValueAsText()));
    							wildcard = wildcard & (~OFMatch.OFPFW_NW_SRC_MASK);
    						}
    						field = node.get("ip-src-mask");
    						if(field != null){
    							short mask = (short)field.getValueAsInt();
    							mask = mask>32?32:mask;
    							wildcard = wildcard | (~OFMatch.OFPFW_NW_SRC_MASK | ((32-mask) << OFMatch.OFPFW_NW_SRC_SHIFT));
    						}
    						field = node.get("ip-dst");
    						if(field != null){
    							rule.setNetworkDestination(IPv4.toIPv4Address(field.getValueAsText()));
    							wildcard = wildcard & (~OFMatch.OFPFW_NW_DST_MASK);
    						}
    						field = node.get("ip-dst-mask");
    						if(field != null){
    							short mask = (short)field.getValueAsInt();
    							mask = mask>32?32:mask;
    							wildcard = wildcard | (~OFMatch.OFPFW_NW_DST_MASK | ((32-mask) << OFMatch.OFPFW_NW_DST_SHIFT));
    						}
    						rule.setWildcards(wildcard);
    						rules.add(rule);
    						logger.info(rule.toString());
    					}
       				}else {
       			        System.out.println("Unprocessed property: " + tmpS);
       			        jp.skipChildren();
       				}
    			}else if(tmpS == "speed"){
					speed = Short.parseShort(jp.getText());
				}
    		}catch(JsonParseException e){
    			logger.debug("Error getting current FIELD_NAME {}", e);
    		}catch(IOException e){
    			logger.debug("Error procession Json {}", e);
    		}
    		
    	}
    	return new Policy(rules, speed);
    }

}
