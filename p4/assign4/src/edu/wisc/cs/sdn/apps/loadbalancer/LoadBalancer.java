package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.ArpServer;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.util.MACAddress;

import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.IPv4;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import edu.wisc.cs.sdn.apps.l3routing.L3Routing;
import org.openflow.protocol.*;
import org.openflow.protocol.OFMatch.*;
import org.openflow.protocol.instruction.OFInstructionGotoTable;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import java.util.*;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;


public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
			 IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();

	private static final byte TCP_FLAG_SYN = 0x02;

	private static final short IDLE_TIMEOUT = 20;

	// Interface to the logging system
	private static Logger log = LoggerFactory.getLogger(MODULE_NAME);

	// Interface to Floodlight core for interacting with connected switches
	private IFloodlightProviderService floodlightProv;

	// Interface to device manager service
	private IDeviceService deviceProv;

	// Switch table in which rules should be installed
	private byte table;

	// Set of virtual IPs and the load balancer instances they correspond with
	private Map<Integer,LoadBalancerInstance> instances;

	/**
	 * Loads dependencies and initializes data structures.
	 */
	@Override
		public void init(FloodlightModuleContext context)
		throws FloodlightModuleException
		{
			log.info(String.format("Initializing %s...", MODULE_NAME));

			// Obtain table number from config
			Map<String,String> config = context.getConfigParams(this);
			this.table = Byte.parseByte(config.get("table"));

			// Create instances from config
			this.instances = new HashMap<Integer,LoadBalancerInstance>();
			String[] instanceConfigs = config.get("instances").split(";");
			for (String instanceConfig : instanceConfigs)
			{
				String[] configItems = instanceConfig.split(" ");
				if (configItems.length != 3)
				{
					log.error("Ignoring bad instance config: " + instanceConfig);
					continue;
				}
				LoadBalancerInstance instance = new LoadBalancerInstance(
						configItems[0], configItems[1], configItems[2].split(","));
				this.instances.put(instance.getVirtualIP(), instance);
				log.info("Added load balancer instance: " + instance);
			}

			this.floodlightProv = context.getServiceImpl(
					IFloodlightProviderService.class);
			this.deviceProv = context.getServiceImpl(IDeviceService.class);

		}

	/**
	 * Subscribes to events and performs other startup tasks.
	 */
	@Override
		public void startUp(FloodlightModuleContext context)
		throws FloodlightModuleException
		{
			log.info(String.format("Starting %s...", MODULE_NAME));
			this.floodlightProv.addOFSwitchListener(this);
			this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);

		}

	/**
	 * Event handler called when a switch joins the network.
	 * @param DPID for the switch
	 */
	@Override
		public void switchAdded(long switchId)
		{
			IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
			log.info(String.format("Switch s%d added", switchId));


			// handle connections to the virtual balancer, TCP packet
			for(int virtual_ip : instances.keySet()) {
				OFMatch handle_ip = new OFMatch();
				handle_ip.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
				handle_ip.setNetworkDestination(OFMatch.ETH_TYPE_IPV4, virtual_ip);
				handle_ip.setNetworkProtocol(OFMatch.IP_PROTO_TCP);

				OFActionOutput act_1 = new OFActionOutput(OFPort.OFPP_CONTROLLER);
				List<OFAction> actions_1 = new ArrayList<OFAction>(Arrays.asList(act_1));
				OFInstructionApplyActions instructs_1 = new OFInstructionApplyActions();
				instructs_1.setActions(actions_1);
				List<OFInstruction> ins_1 = new ArrayList<OFInstruction>(Arrays.asList(instructs_1));

				boolean check_ip = SwitchCommands.installRule(sw, this.table, SwitchCommands.DEFAULT_PRIORITY,
						handle_ip, ins_1);
			}

			/*       (2) handle ARP packets to the controller, and                      */

			OFMatch handle_arp = new OFMatch();
			handle_arp.setDataLayerType(OFMatch.ETH_TYPE_ARP);

			OFActionOutput act_2 = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			List<OFAction> actions_2 = new ArrayList<OFAction>(Arrays.asList(act_2));
			OFInstructionApplyActions instructs_2 = new OFInstructionApplyActions();
			instructs_2.setActions(actions_2);
			List<OFInstruction> ins_2 = new ArrayList<OFInstruction>(Arrays.asList(instructs_2));

			boolean check_arp = SwitchCommands.installRule(sw, this.table, SwitchCommands.DEFAULT_PRIORITY,
					handle_arp, ins_2);

			/*       (3) all other packets to the next rule table in the switch  */

			OFMatch handle_other = new OFMatch();
			handle_other.setDataLayerType(Ethernet.TYPE_IPv4);
			OFInstructionGotoTable next_table = new OFInstructionGotoTable(L3Routing.table);
			List<OFInstruction> ins_3 = new ArrayList<OFInstruction>(Arrays.asList(next_table));

			boolean check = SwitchCommands.installRule(sw, this.table, SwitchCommands.DEFAULT_PRIORITY,
					handle_other, ins_3);

		}

	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
		public net.floodlightcontroller.core.IListener.Command receive(
				IOFSwitch sw, OFMessage msg, FloodlightContext cntx)
		{
			// We're only interested in packet-in messages
			if (msg.getType() != OFType.PACKET_IN)
			{ return Command.CONTINUE; }
			OFPacketIn pktIn = (OFPacketIn)msg;

			// Handle the packet
			Ethernet ethPkt = new Ethernet();
			ethPkt.deserialize(pktIn.getPacketData(), 0,
					pktIn.getPacketData().length);



			short etherType = ethPkt.getEtherType();

			//if ARP, send a reply
			if(etherType == Ethernet.TYPE_ARP) {

				//get ARP header
				ARP arpPkt = (ARP)ethPkt.getPayload();

				//if this is not about IP
				if(arpPkt.getProtocolType() != ARP.PROTO_TYPE_IP) return Command.CONTINUE;

				//if it is not a request
				if(arpPkt.getOpCode() != ARP.OP_REQUEST) return Command.CONTINUE;

				//get IP of desired load balancer
				byte[] vIP = arpPkt.getTargetProtocolAddress();
				int virtualIP = IPv4.toIPv4Address(vIP);

				LoadBalancerInstance loadBalancer = instances.get(virtualIP);

				//now we build the reply like we did in P3
				Ethernet eth = new Ethernet();
				ARP reply = new ARP();
				eth.setPayload(reply);

				eth.setSourceMACAddress(loadBalancer.getVirtualMAC());
				eth.setDestinationMACAddress(ethPkt.getSourceMACAddress());
				eth.setEtherType(Ethernet.TYPE_ARP);

				reply.setHardwareType(ARP.HW_TYPE_ETHERNET);
				reply.setProtocolType(ARP.PROTO_TYPE_IP);
				reply.setHardwareAddressLength((byte)Ethernet.DATALAYER_ADDRESS_LENGTH);
				reply.setProtocolAddressLength((byte)4);
				reply.setOpCode(ARP.OP_REPLY);
				reply.setSenderHardwareAddress(loadBalancer.getVirtualMAC());
				reply.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(virtualIP));
				reply.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress());
				reply.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());

				short inPort = (short)pktIn.getInPort();

				SwitchCommands.sendPacket(sw, inPort, eth);
			}
			//if TCP SYNs
			else if(etherType == Ethernet.TYPE_IPv4) {
				//check if TCP

				IPv4 ipPkt = (IPv4) ethPkt.getPayload();
				if (ipPkt.getProtocol() != IPv4.PROTOCOL_TCP) return Command.CONTINUE;

				//check if SYN
				TCP tcpPkt = (TCP) ipPkt.getPayload();
				if (tcpPkt.getFlags() != TCP_FLAG_SYN) return Command.CONTINUE;

				int virtualIP = ipPkt.getDestinationAddress();
				LoadBalancerInstance loadBalancer = instances.get(virtualIP);
				int hostip = loadBalancer.getNextHostIP();


				OFMatch match_2 = new OFMatch();
				match_2.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
				match_2.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
				match_2.setNetworkSource(OFMatch.ETH_TYPE_IPV4, ipPkt.getSourceAddress());
				match_2.setNetworkDestination(OFMatch.ETH_TYPE_IPV4, ipPkt.getDestinationAddress());
				match_2.setTransportSource(tcpPkt.getSourcePort());
				match_2.setTransportDestination(tcpPkt.getDestinationPort());

				OFActionSetField ethAction_2 = new OFActionSetField(OFOXMFieldType.ETH_DST, getHostMACAddress(hostip));
				OFActionSetField ipAction_2 = new OFActionSetField(OFOXMFieldType.IPV4_DST, hostip);
				OFInstructionApplyActions applyActions_2 = new OFInstructionApplyActions();
				applyActions_2.setActions(new ArrayList<OFAction>(Arrays.asList(ipAction_2, ethAction_2)));

				OFInstructionGotoTable gotoTable_2 = new OFInstructionGotoTable(L3Routing.table);

				ArrayList<OFInstruction> instructions_2 =  new ArrayList<OFInstruction>(Arrays.asList(applyActions_2, gotoTable_2));
				SwitchCommands.installRule(sw, table, SwitchCommands.MAX_PRIORITY,
						match_2, instructions_2, (short)20, (short)20);


				OFMatch match_1 = new OFMatch();
				match_1.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
				match_1.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
				match_1.setNetworkSource(OFMatch.ETH_TYPE_IPV4, hostip);
				match_1.setNetworkDestination(OFMatch.ETH_TYPE_IPV4, ipPkt.getSourceAddress());
				match_1.setTransportSource(tcpPkt.getDestinationPort());
				match_1.setTransportDestination(tcpPkt.getSourcePort());

				OFActionSetField ethAction_1 = new OFActionSetField(OFOXMFieldType.ETH_SRC, loadBalancer.getVirtualMAC());
				OFActionSetField ipAction_1 = new OFActionSetField(OFOXMFieldType.IPV4_SRC, loadBalancer.getVirtualIP());
				OFInstructionApplyActions applyActions_1 = new OFInstructionApplyActions();

				applyActions_1.setActions(new ArrayList<OFAction>(Arrays.asList(ipAction_1, ethAction_1)));
				OFInstructionGotoTable gotoTable_1 = new OFInstructionGotoTable(L3Routing.table);

				ArrayList<OFInstruction> instructions_1 =  new ArrayList<OFInstruction>(Arrays.asList(applyActions_1, gotoTable_1));
				SwitchCommands.installRule(sw, table, SwitchCommands.MAX_PRIORITY, match_1, instructions_1, (short)20, (short)20);


			}
			return Command.CONTINUE;
		}


	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
		public void switchRemoved(long switchId)
		{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
		public void switchActivated(long switchId)
		{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
		public void switchPortChanged(long switchId, ImmutablePort port,
				PortChangeType type)
		{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
		public void switchChanged(long switchId)
		{ /* Nothing we need to do */ }

	/**
	 * Tell the module system which services we provide.
	 */
	@Override
		public Collection<Class<? extends IFloodlightService>> getModuleServices()
		{ return null; }

	/**
	 * Tell the module system which services we implement.
	 */
	@Override
		public Map<Class<? extends IFloodlightService>, IFloodlightService>
		getServiceImpls()
		{ return null; }

	/**
	 * Tell the module system which modules we depend on.
	 */
	@Override
		public Collection<Class<? extends IFloodlightService>>
		getModuleDependencies()
		{
			Collection<Class<? extends IFloodlightService >> floodlightService =
				new ArrayList<Class<? extends IFloodlightService>>();
			floodlightService.add(IFloodlightProviderService.class);
			floodlightService.add(IDeviceService.class);
			return floodlightService;
		}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
		public String getName()
		{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
		public boolean isCallbackOrderingPrereq(OFType type, String name)
		{
			return (OFType.PACKET_IN == type
					&& (name.equals(ArpServer.MODULE_NAME)
						|| name.equals(DeviceManagerImpl.MODULE_NAME)));
		}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
		public boolean isCallbackOrderingPostreq(OFType type, String name)
		{ return false; }
}
