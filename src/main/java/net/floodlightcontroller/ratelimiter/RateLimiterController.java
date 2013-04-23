package net.floodlightcontroller.ratelimiter;

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

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPacketIn;

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
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;

public class RateLimiterController extends Forwarding implements RateLimiterService {
	private Map<Integer, Policy> policyStorage;
	private Map<Integer, Flow> flowStorage;
	private Map<Integer, HashSet<Policy>> subSets;
	private Map<SwitchPair, Integer> distance;
	
	public boolean flowBelongsToRule(OFMatch flow, OFMatch rule){
		int rulewc = rule.getWildcards();
		if(!((rulewc & OFMatch.OFPFW_IN_PORT) == OFMatch.OFPFW_IN_PORT) || 
				rule.getInputPort() == flow.getInputPort())
			return false;
		if(!((rulewc & OFMatch.OFPFW_DL_VLAN) == OFMatch.OFPFW_DL_VLAN) || 
				rule.getDataLayerVirtualLan() == flow.getDataLayerVirtualLan())
			return false;
		if(!((rulewc & OFMatch.OFPFW_DL_SRC) == OFMatch.OFPFW_DL_SRC) || 
				Arrays.equals(rule.getDataLayerSource(), flow.getDataLayerSource()))
			return false;
		if(!((rulewc & OFMatch.OFPFW_DL_DST) == OFMatch.OFPFW_DL_DST) || 
				Arrays.equals(rule.getDataLayerDestination(), flow.getDataLayerDestination()))
			return false;
		if(!((rulewc & OFMatch.OFPFW_DL_TYPE) == OFMatch.OFPFW_DL_TYPE) || 
				rule.getDataLayerType() == flow.getDataLayerType())
			return false;
		if(!((rulewc & OFMatch.OFPFW_NW_PROTO) == OFMatch.OFPFW_NW_PROTO) || 
				rule.getNetworkProtocol() == flow.getNetworkProtocol())
			return false;
		if(!((rulewc & OFMatch.OFPFW_TP_SRC) == OFMatch.OFPFW_TP_SRC) || 
				rule.getTransportSource() == flow.getTransportSource())
			return false;
		if(!((rulewc & OFMatch.OFPFW_TP_DST) == OFMatch.OFPFW_TP_DST) || 
				rule.getTransportDestination() == flow.getTransportDestination())
			return false;
		
		int ruleSrcMask = rule.getNetworkSourceMaskLen();
		int matchSrcMask = flow.getNetworkSourceMaskLen();
		if(!(ruleSrcMask >= matchSrcMask && 
				(rule.getNetworkSource() & (0xffffffff << ruleSrcMask)) ==
				(flow.getNetworkSource() & (0xffffffff << matchSrcMask))))
			return false;
		int ruleDstMask = rule.getNetworkDestinationMaskLen();
		int matchDstMask = flow.getNetworkDestinationMaskLen();
		if(!(ruleDstMask >= matchDstMask && 
				(rule.getNetworkDestination() & (0xffffffff << ruleDstMask)) ==
				(flow.getNetworkSource() & (0xffffffff << matchDstMask))))
			return false;
		
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
				if(flowBelongsToRule(match, (OFMatch) itr.next()))
					matchedPolicies.add(policytmp);
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
		OFMatch match = new OFMatch();
		match.loadFromPacket(pi.getPacketData(), pi.getInPort());
		//TODO match packet in the ruleStorage and decide what to do next.
		Set<Policy> policies = matchPoliciesFromStorage(match);
		if(policies.isEmpty()) return false;
		
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


	public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision,
            FloodlightContext cntx) {
    	/* First check if the packet match the existing rules. 
    	 * If it does then process it, otherwise forward it as default packet
    	 */
    	if(processPacket(sw, pi, cntx) == true) return Command.CONTINUE;
    	
    	Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, 
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		// If a decision has been made we obey it
		// otherwise we just forward
		if (decision != null) {
			if (log.isTraceEnabled()) {
				log.trace("Forwaring decision={} was made for PacketIn={}",
						decision.getRoutingAction().toString(),
						pi);
			}
		
			switch(decision.getRoutingAction()) {
			case NONE:
				// don't do anything
				return Command.CONTINUE;
			case FORWARD_OR_FLOOD:
			case FORWARD:
				doForwardFlow(sw, pi, cntx, false);
				return Command.CONTINUE;
			case MULTICAST:
				// treat as broadcast
				doFlood(sw, pi, cntx);
				return Command.CONTINUE;
			case DROP:
				doDropFlow(sw, pi, decision, cntx);
				return Command.CONTINUE;
			default:
				log.error("Unexpected decision made for this packet-in={}",
			         pi, decision.getRoutingAction());
				return Command.CONTINUE;
			}
		} else {
			if (log.isTraceEnabled()) {
				log.trace("No decision was made for PacketIn={}, forwarding",
						pi);
			}
		
			if (eth.isBroadcast() || eth.isMulticast()) {
				// For now we treat multicast as broadcast
				doFlood(sw, pi, cntx);
			} else {
				doForwardFlow(sw, pi, cntx, false);
			}
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
        
        this.policyStorage = new HashMap<Integer, Policy>();
    	this.flowStorage = new HashMap<Integer, Flow>();
    	
    	this.distance = initAllPairDistance();

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

	@Override
	public void addPolicy(Policy p) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deletePolicy(Policy p) {
		// TODO Auto-generated method stub
		
	}
}
