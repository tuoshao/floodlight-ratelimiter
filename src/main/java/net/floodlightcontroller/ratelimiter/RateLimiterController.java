package net.floodlightcontroller.ratelimiter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.*;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionEnqueue;
import org.openflow.protocol.action.OFActionType;
import org.openflow.util.HexString;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.forwarding.Forwarding;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.benchmarkcontroller.IQueueCreaterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RateLimiterController extends Forwarding {
	private Map<Integer, Policy> policyStorage;
	private Map<Integer, Flow> flowStorage;
	private Map<Integer, HashSet<Policy>> subSets;
	private Map<SwitchPair, Integer> distance;
    private static Logger log = LoggerFactory.getLogger(RateLimiterController.class);
    private ILinkDiscoveryService linkService;
    private int installed = 0;
    protected IQueueCreaterService queueCreaterService;

	public boolean flowBelongsToRule(OFMatch flow, OFMatch rule){
        log.warn("flow: " + flow.toString() + " rule: " + rule.toString());

		int rulewc = rule.getWildcards();
		if(!(((rulewc & OFMatch.OFPFW_IN_PORT) == OFMatch.OFPFW_IN_PORT) || 
				rule.getInputPort() == flow.getInputPort()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_VLAN) == OFMatch.OFPFW_DL_VLAN) || 
				rule.getDataLayerVirtualLan() == flow.getDataLayerVirtualLan()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_SRC) == OFMatch.OFPFW_DL_SRC) || 
				Arrays.equals(rule.getDataLayerSource(), flow.getDataLayerSource())))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_DST) == OFMatch.OFPFW_DL_DST) || 
				Arrays.equals(rule.getDataLayerDestination(), flow.getDataLayerDestination())))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_DL_TYPE) == OFMatch.OFPFW_DL_TYPE) || 
				rule.getDataLayerType() == flow.getDataLayerType()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_NW_PROTO) == OFMatch.OFPFW_NW_PROTO) || 
				rule.getNetworkProtocol() == flow.getNetworkProtocol()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_TP_SRC) == OFMatch.OFPFW_TP_SRC) || 
				rule.getTransportSource() == flow.getTransportSource()))
			return false;

		if(!(((rulewc & OFMatch.OFPFW_TP_DST) == OFMatch.OFPFW_TP_DST) || 
				rule.getTransportDestination() == flow.getTransportDestination()))
			return false;
		
		int ruleSrcMask = rule.getNetworkSourceMaskLen();
		int matchSrcMask = flow.getNetworkSourceMaskLen();
		log.warn(String.valueOf(ruleSrcMask));
		log.warn(String.valueOf(matchSrcMask));

		log.warn(String.valueOf((0xffffffff << (3))));
		log.warn(String.valueOf((1 << (33))));

		
		if(!(ruleSrcMask <= matchSrcMask &&
				(rule.getNetworkSource() & ((ruleSrcMask==0)? 0:0xffffffff << (32-ruleSrcMask))) ==
				(flow.getNetworkSource() & ((ruleSrcMask==0)? 0:0xffffffff << (32-ruleSrcMask)))))
			return false;
		log.warn("match src");
		int ruleDstMask = rule.getNetworkDestinationMaskLen();
		int matchDstMask = flow.getNetworkDestinationMaskLen();
		if(!(ruleDstMask <= matchDstMask &&
				(rule.getNetworkDestination() & ((ruleDstMask==0)? 0:0xffffffff << (32-ruleDstMask))) ==
				(flow.getNetworkDestination() & ((ruleDstMask==0)? 0:0xffffffff << (32-ruleDstMask)))))
			return false;
		log.warn("match dst");

		return true;
	}
	
	private boolean checkIfPolicyCoexist(Policy p1, Policy p2){
		for(Flow flowp1:p1.flows){
			for(OFMatch rule:p2.rules){
				if(flowBelongsToRule(flowp1.match, rule)) {
					return false;
				}
			}
		}
		
		for(Flow flowp2:p2.flows){
			for(OFMatch rule:p1.rules){
				if(flowBelongsToRule(flowp2.match, rule)){
					return false;
				}
			}
		}
		return true;
	}

	private Set<Policy> matchPoliciesFromStorage(OFMatch match){
		Set<Policy> matchedPolicies= new HashSet<Policy>();
		Iterator itp = policyStorage.values().iterator();
		while(itp.hasNext()){
			Policy policytmp = (Policy) itp.next();
			if(policytmp.flows.contains(Integer.valueOf(match.hashCode())))
				continue;
			Iterator itr = policytmp.rules.iterator();
			while(itr.hasNext()){
				if(flowBelongsToRule(match, (OFMatch) itr.next())) {
					matchedPolicies.add(policytmp);
                }
			}
			
		}
		return matchedPolicies;
	}
	
	/*private Set<Policy> getPoliciesFromRules(Set<Rule> rules){
		Set<Policy> matchedPolicies = new HashSet<Policy>();
		Iterator it = rules.iterator();
		while(it.hasNext()){
			Rule rule = (Rule) it.next();
			Iterator itRule = rule.policies.iterator();
			while(itRule.hasNext()){
				Integer policyHashCode = (Integer) itRule.next();
				matchedPolicies.add(policyStorage.get(policyHashCode));
			}
		}
		return matchedPolicies;
	}*/ 
	
	private boolean processPacket(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx){
        log.warn("process packet at " + sw.toString());

        IDevice dstDevice =
                IDeviceService.fcStore.
                        get(cntx, IDeviceService.CONTEXT_DST_DEVICE);

        //We can't handle packets with unknown destination
        if (dstDevice == null) {
            return false;
        }

		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());

		//TODO match packet in the ruleStorage and decide what to do next.
		Set<Policy> policies = matchPoliciesFromStorage(match);


		if(policies.isEmpty()) return false;

        SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
        SwitchPort dstsw = dstDaps[0];
        Map<Long, IOFSwitch> switches = floodlightProvider.getSwitches();
        IOFSwitch s2 = switches.get(Long.valueOf(2));
        NodePortTuple s2tuple = new NodePortTuple(s2.getId(), Short.valueOf((short) 2));
        Set<Link> s2links = linkService.getPortLinks().get(s2tuple);
        NodePortTuple s2next = null;
        for (Link l : s2links) {
            if (l.getSrc() == s2.getId() && l.getSrcPort() == 2) {
                s2next = new NodePortTuple(l.getDst(), l.getDstPort());
                break;
            }
        }


        Route r1_temp = routingEngine.getRoute(sw.getId(), pi.getInPort(), s2.getId(), (short)2, 0);
        Route r1 = new Route(r1_temp.getId(), r1_temp.getPath());
        int r1len = r1.getPath().size();
        r1.getPath().remove(r1len-1);
        short s2inport = r1.getPath().get(r1len-2).getPortId();
        r1.getPath().remove(r1len-2);

        if(!policies.isEmpty()){
            if(installed < 2){
                installed ++;
            }
            if(installed == 2){
                for (Policy p : policies) {
                    OFMatch s2match = match.clone();
                    s2match.setInputPort(s2inport);
                    installMatchedFLowToSwitch(s2match, s2, p);
                }
                installed ++;
            }
        }

        Route r2 = routingEngine.getRoute(s2next.getNodeId(), s2next.getPortId(), dstsw.getSwitchDPID(), (short) dstsw.getPort(), 0);
        log.warn(r2.getPath().toString());
        long cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);
        pushRoute(r1, match, match.getWildcards(), pi, sw.getId(), cookie,
                cntx, false, false, OFFlowMod.OFPFC_ADD);
        pushRoute(r2, match, match.getWildcards(), pi, sw.getId(), cookie,
                cntx, false, false, OFFlowMod.OFPFC_ADD);

    /*
		IDevice srcDevice =
                IDeviceService.fcStore.
                    get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);
		
		IDevice dstDevice = 
                IDeviceService.fcStore.
                    get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
        SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
        SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
        SwitchPort srcSwitchPort = srcDaps[0];
        SwitchPort dstSwitchPort = dstDaps[0];
        NodePortTuple srcNodePort = new NodePortTuple(srcSwitchPort.getSwitchDPID(),srcSwitchPort.getPort());
        NodePortTuple dstNodePort = new NodePortTuple(dstSwitchPort.getSwitchDPID(),dstSwitchPort.getPort());

		
		Flow newflow = new Flow(match, srcNodePort, dstNodePort);
		//flowStorage.put(Integer.valueOf(match.hashCode()), newflow);
		Set<Policy> policiesToDelete = processFlowWithPolicy(newflow, policies);
		if(!policiesToDelete.isEmpty()){
			deletePolicyFromStorage(policiesToDelete);
		}
		
*/
		return true;
		
	}
	
	private void deletePolicyFromStorage(Set<Policy> policiesToDelete) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Divide policies into sets according the switches they are installed
	 * non-installed policies are in one set
	 * @param policies
	 * @param flow
	 * @return
	 */
    private List<ArrayList<Policy>> dividePolicyBySwitch(Set<Policy> policies, Flow flow) {
		// TODO Here we simply order the list by switch ID. A better way is to order the switches
    	// in the sequence that flow could travel through in the shortest distance.
    	boolean inserted = false;
    	int i = 0;
    	List<ArrayList<Policy>> policySet = new LinkedList<ArrayList<Policy>>();
    	Iterator itp = policies.iterator();
    	while(itp.hasNext()){
    		Policy p = (Policy) itp.next();
    		Iterator itl = policySet.iterator();
    		ArrayList<Policy> policySameSwitch;
    		while(itl.hasNext()){
    			policySameSwitch = (ArrayList<Policy>) itl.next();
    			if(policySameSwitch.get(0).dpid == p.dpid){
    				policySameSwitch.add(p);
    				inserted = true;
    			}
    			if(policySameSwitch.get(0).dpid < p.dpid){
    				i++;
    				continue;
    			}
    			else{
    				i--;
    				break;
    			}
    		}
    		if(inserted == false){
    			policySet.add(i, new ArrayList<Policy>());
    		}else{
    			inserted = false;
    		}
    	}
		return policySet;
	}

    /**
     * Process and update the flow and the matching policies.
     * @param flow
     * @param policies
     * @return
     */
	private Set<Policy> processFlowWithPolicy(Flow flow, Set<Policy> policies) {
		// TODO Auto-generated method stub
		Set<Policy> policiesToDelete = new HashSet<Policy>();
		List<ArrayList<Policy>> policySet = dividePolicyBySwitch(policies, flow);

		//List<Integer> switches = getSwitchByPolicy(policySet);
		
		// Add sets of policies to the new flow
		Iterator it = policySet.iterator();
		while(it.hasNext()){
			ArrayList<Policy> policySameSwitch = (ArrayList<Policy>) it.next();
			
			if(policySameSwitch.get(0).dpid != Long.MAX_VALUE){
				// TODO We could apply some strategies here to decide which policy to stay in the same switch
				Policy p = policySameSwitch.get(0);
				updatePolicyWithFlow(flow, p);
				flow.addPolicy(p);
				int i = 1;
				int size = policySameSwitch.size();
				while(i<size){
					p = policySameSwitch.get(i);
					updatePolicyWithFlow(flow, p);
					if(findNewSwitch(p) == false){
						policiesToDelete.add(p);
						deleteFlowFromPolicy(p, flow);
					} else {
						flow.addPolicy(p);
					}
					i++;
				}
			} else {
				Route route = flow.getRoute();
				int i = 0;
				int size = policySameSwitch.size();
				while(i<size){
					Policy p = policySameSwitch.get(i);
					if(findNewSwitch(p, route) == false){
						policiesToDelete.add(p);
					}else{
						updatePolicyWithFlow(flow, p);
						flow.addPolicy(p);
					}
					i++;
				}
			}
		}
		if(!flow.getPoliy().isEmpty()) {
			flowStorage.put(flow.hashCode(), flow);
		}
		// we can also implement a optimization here to determine
		// whether the switches are affecting the route of flow too much
		return policiesToDelete;
	}
	
	private void installMatchedFLowToSwitch(OFMatch flow, IOFSwitch sw, Policy p){
		log.warn("Trying to install policies!!!!!!!!!!!!!!!!");

        OFFlowMod fm = new OFFlowMod();
        fm.setType(OFType.FLOW_MOD);

        List<OFAction> actions = new ArrayList<OFAction>();

        //add the queuing action
        OFActionEnqueue enqueue = new OFActionEnqueue();
        enqueue.setLength((short)OFActionEnqueue.MINIMUM_LENGTH);
        enqueue.setType(OFActionType.OPAQUE_ENQUEUE); // I think this happens anyway in the constructor
        enqueue.setPort(p.port);
        enqueue.setQueueId(p.queue);
        actions.add((OFAction) enqueue);

        fm.setMatch(flow)
            .setActions(actions)
            .setIdleTimeout((short) 0)  // infinite
            .setHardTimeout((short) 0)  // infinite
            .setBufferId(OFPacketOut.BUFFER_ID_NONE)
            .setFlags((short) 0)
            .setOutPort(OFPort.OFPP_NONE.getValue())
            .setPriority(p.priority)
            .setLengthU(OFFlowMod.MINIMUM_LENGTH+OFActionEnqueue.MINIMUM_LENGTH);
        try {
            sw.write(fm, null);
            sw.flush();
        } catch (IOException e) {
            log.error("Tried to write OFFlowMod to {} but failed: {}",
                    HexString.toHexString(sw.getId()), e.getMessage());
        }

				
	}

	private boolean findNewSwitch(Policy p, Route route) {
		// TODO Auto-generated method stub
		List<NodePortTuple> path = route.getPath();
		
		return false;
	}

	private void deleteFlowFromPolicy(Policy p, Flow flow) {
		// TODO Auto-generated method stub
		
	}

	private boolean findNewSwitch(Policy p) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * Add new flow information to the policy
	 * @param flow
	 * @param p
	 */
	private void updatePolicyWithFlow(Flow flow, Policy p) {
		// TODO Auto-generated method stub
		
	}


	/*public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
            FloodlightContext cntx) {
    	*//* First check if the packet match the existing rules.
    	 * If it does then process it, otherwise forward it as default packet
    	 *//*
        if(processPacket(sw, pi, cntx)) {
            return Command.CONTINUE;
        }

        return super.processPacketInMessage(sw, pi, decision, cntx);
    }*/

    @Override
    protected void doForwardFlow(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
        if (processPacket(sw, pi, cntx)) return;
        else super.doForwardFlow(sw, pi, cntx, requestFlowRemovedNotifn);
    }
    
    private Command handleFlowRemoved(IOFSwitch sw, OFFlowRemoved msg, FloodlightContext cntx) {
        OFMatch flow = msg.getMatch();
        Iterator it = policyStorage.values().iterator();
        while(it.hasNext()){
        	Policy p = (Policy) it.next();
        	for(Flow f:p.flows){
        		if(f.policies.containsValue(flow)){
        			p.flows.remove(flow);
        		}
        	}
        }
        
        return Command.CONTINUE;
    }
    
    @Override
    public Command receive(IOFSwitch sw, OFMessage msg,
                           FloodlightContext cntx) {
        switch (msg.getType()) {
            case PACKET_IN:
                IRoutingDecision decision = null;
                if (cntx != null)
                     decision =
                             IRoutingDecision.rtStore.get(cntx,
                                                          IRoutingDecision.CONTEXT_DECISION);

                return this.processPacketInMessage(sw,
                                                   (OFPacketIn) msg,
                                                   decision,
                                                   cntx);
            case FLOW_REMOVED:
                return handleFlowRemoved(sw, (OFFlowRemoved) msg, cntx);
            default:
                break;
        }
        return Command.CONTINUE;
    }
    
    

    private Map<SwitchPair, Integer> initAllPairDistance() {
		// TODO Auto-generated method stub
		Map<SwitchPair, Integer> distances = new HashMap<SwitchPair, Integer>();
        Map<Long, IOFSwitch> switches = floodlightProvider.getSwitches();
		Iterator it1 = switches.keySet().iterator();
		while(it1.hasNext()){
			Long swId1 = (Long) it1.next();
			Iterator it2 = switches.keySet().iterator();
			while(it2.hasNext()){
				Long swId2 = (Long) it2.next();
				SwitchPair sp = new SwitchPair(swId1.longValue(), swId2.longValue());
				if(!distances.containsKey(sp)){
					distances.put(sp , routingEngine.getRoute(swId1.longValue(), swId2.longValue(), 0).getPath().size()/2);
				}
			}
		}
		return distances;
	}
    
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        super.init();
        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceManager = context.getServiceImpl(IDeviceService.class);
        this.routingEngine = context.getServiceImpl(IRoutingService.class);
        this.topology = context.getServiceImpl(ITopologyService.class);
        this.counterStore = context.getServiceImpl(ICounterStoreService.class);
        this.linkService = context.getServiceImpl(ILinkDiscoveryService.class);
        this.queueCreaterService = context.getServiceImpl(IQueueCreaterService.class);
        this.policyStorage = new HashMap<Integer, Policy>();
    	this.flowStorage = new HashMap<Integer, Flow>();
    	
    	this.distance = initAllPairDistance();
    	
        OFMatch temp_match = new OFMatch();
        temp_match.setWildcards(~(OFMatch.OFPFW_NW_DST_MASK | OFMatch.OFPFW_NW_SRC_MASK));
        temp_match.setNetworkDestination(167772164);
        temp_match.setNetworkSource(167772161);

        Set<OFMatch> temp_policyset = new HashSet<OFMatch>();
        temp_policyset.add(temp_match);
        Policy temp_policy = new Policy(temp_policyset, (short)1);
        temp_policy.setPort((short)2);
        temp_policy.setQueue(1);
        policyStorage.put(Integer.valueOf(temp_policy.hashCode()), temp_policy);

        // read our config options
        Map<String, String> configOptions = context.getConfigParams(this);
        try {
            String idleTimeout = configOptions.get("idletimeout");
            if (idleTimeout != null) {
                FLOWMOD_DEFAULT_IDLE_TIMEOUT = Short.parseShort(idleTimeout);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing flow idle timeout, " +
            		 "using default of {} seconds",
                     FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        }
        try {
            String hardTimeout = configOptions.get("hardtimeout");
            if (hardTimeout != null) {
                FLOWMOD_DEFAULT_HARD_TIMEOUT = Short.parseShort(hardTimeout);
            }
        } catch (NumberFormatException e) {
            log.warn("Error parsing flow hard timeout, " +
            		 "using default of {} seconds",
                     FLOWMOD_DEFAULT_HARD_TIMEOUT);
        }
        log.debug("FlowMod idle timeout set to {} seconds", 
                  FLOWMOD_DEFAULT_IDLE_TIMEOUT);
        log.debug("FlowMod hard timeout set to {} seconds", 
                  FLOWMOD_DEFAULT_HARD_TIMEOUT);
    }
}
