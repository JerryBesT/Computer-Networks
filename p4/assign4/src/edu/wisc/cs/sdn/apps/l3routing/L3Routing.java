
package edu.wisc.cs.sdn.apps.l3routing;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class L3Routing implements IFloodlightModule, IOFSwitchListener,
			 ILinkDiscoveryListener, IDeviceListener
{
	public static final String MODULE_NAME = L3Routing.class.getSimpleName();

	// Interface to the logging system
	private static Logger log = LoggerFactory.getLogger(MODULE_NAME);

	// Interface to Floodlight core for interacting with connected switches
	private IFloodlightProviderService floodlightProv;

	// Interface to link discovery service
	private ILinkDiscoveryService linkDiscProv;

	// Interface to device manager service
	private IDeviceService deviceProv;

	// Switch table in which rules should be installed
	public static byte table;

	// Map of hosts to devices
	private Map<IDevice,Host> knownHosts;

	/**
	 * Loads dependencies and initializes data structures.
	 */
	@Override
		public void init(FloodlightModuleContext context)
		throws FloodlightModuleException
		{
			log.info(String.format("Initializing %s...", MODULE_NAME));
			Map<String,String> config = context.getConfigParams(this);
			table = Byte.parseByte(config.get("table"));

			this.floodlightProv = context.getServiceImpl(
					IFloodlightProviderService.class);
			this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
			this.deviceProv = context.getServiceImpl(IDeviceService.class);

			this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
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
			this.linkDiscProv.addListener(this);
			this.deviceProv.addListener(this);
		}

	/**
	 * Get a list of all known hosts in the network.
	 */
	private Collection<Host> getHosts()
	{ return this.knownHosts.values(); }

	/**
	 * Get a map of all active switches in the network. Switch DPID is used as
	 * the key.
	 */
	private Map<Long, IOFSwitch> getSwitches()
	{ return floodlightProv.getAllSwitchMap(); }

	/**
	 * Get a list of all active links in the network.
	 */
	private Collection<Link> getLinks()
	{ return linkDiscProv.getLinks().keySet(); }

	/**
	 * Event handler called when a host joins the network.
	 * @param device information about the host
	 */
	@Override
		public void deviceAdded(IDevice device)
		{
			Host host = new Host(device, this.floodlightProv);
			// We only care about a new host if we know its IP
			if (host.getIPv4Address() != null)
			{
				log.info(String.format("Host %s added", host.getName()));
				this.knownHosts.put(device, host);
				HashMap<IOFSwitch, Integer> path = bellman(host);
				this.install_rules(path, host);

			}
		}

	/**
	 * Event handler called when a host is no longer attached to a switch.
	 * @param device information about the host
	 */
	@Override
		public void deviceRemoved(IDevice device)
		{
			Host host = this.knownHosts.get(device);
			if (null == host)
			{ return; }
			this.knownHosts.remove(device);

			log.info(String.format("Host %s is no longer attached to a switch",
						host.getName()));

			if(host.getIPv4Address() == null) return;

			Map<Long, IOFSwitch> switches = this.getSwitches();
			for(IOFSwitch curr_switch : switches.values()) {
				OFMatch match = new OFMatch();
				match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
				match.setNetworkDestination(host.getIPv4Address());
				SwitchCommands.removeRules(curr_switch, this.table, match);
			}

		}

	/**
	 * Event handler called when a host moves within the network.
	 * @param device information about the host
	 */
	@Override
		public void deviceMoved(IDevice device)
		{
			Host host = this.knownHosts.get(device);
			if (null == host)
			{
				host = new Host(device, this.floodlightProv);
				this.knownHosts.put(device, host);
			}

			if (!host.isAttachedToSwitch())
			{
				this.deviceRemoved(device);
				return;
			}
			log.info(String.format("Host %s moved to s%d:%d", host.getName(),
						host.getSwitch().getId(), host.getPort()));

			HashMap<IOFSwitch, Integer> path = bellman(host);
			this.install_rules(path, host);
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

			Collection<Host> hosts = this.getHosts();
			for(Host h : hosts) {
				HashMap<IOFSwitch, Integer> path = bellman(h);
				this.install_rules(path, h);
			}
		}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
		public void switchRemoved(long switchId)
		{
			IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
			log.info(String.format("Switch s%d removed", switchId));

			Collection<Host> hosts = this.getHosts();
			for(Host h : hosts) {
				HashMap<IOFSwitch, Integer> path = bellman(h);
				this.install_rules(path, h);
			}
		}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
		public void linkDiscoveryUpdate(List<LDUpdate> updateList)
		{
			for (LDUpdate update : updateList)
			{
				// If we only know the switch & port for one end of the link, then
				// the link must be from a switch to a host
				if (0 == update.getDst())
				{
					log.info(String.format("Link s%s:%d -> host updated",
								update.getSrc(), update.getSrcPort()));
				}
				// Otherwise, the link is between two switches
				else
				{
					log.info(String.format("Link s%s:%d -> s%s:%d updated",
								update.getSrc(), update.getSrcPort(),
								update.getDst(), update.getDstPort()));
				}
			}

			Collection<Host> hosts = this.getHosts();
			for(Host h : hosts) {
				HashMap<IOFSwitch, Integer> path = bellman(h);
				this.install_rules(path, h);
			}
		}

	private HashMap<IOFSwitch, Integer> bellman(Host srcHost) {
		Map<Long, IOFSwitch> switches = getSwitches();
		Collection<Link> links = this.getLinks();
		HashMap<IOFSwitch, Integer> result = new HashMap<IOFSwitch, Integer>();
		HashMap<IOFSwitch, Integer> distance = new HashMap<IOFSwitch, Integer>();

		//initializing
		for(IOFSwitch curr_switch : switches.values()) {
			if(srcHost.getSwitch() != curr_switch) {
				distance.put(curr_switch, 1073741824);
			} else {
				//the distance from a host to its attached switch is 1
				distance.put(curr_switch, 1);
			}
		}
		result.put(srcHost.getSwitch(), srcHost.getPort());

		//check if host is attached to a switch
		//and host ip address is not null
		if(!srcHost.isAttachedToSwitch() || srcHost.getIPv4Address() == null)
			return null;

		for(int i = 0 ; i < switches.size()-1 ; i++) {
			//for each link
			for(Link l : links) {
				IOFSwitch srcSwitch = switches.get(l.getSrc());
				IOFSwitch dstSwitch = switches.get(l.getDst());
				int srcPort = l.getSrcPort();
				int dstPort = l.getDstPort();

				int temp = distance.get(dstSwitch)+1;
				if(temp < distance.get(srcSwitch)) {
					distance.put(srcSwitch, temp);
					result.put(srcSwitch, srcPort);
				}
				//since links are bidirectional
				temp = distance.get(srcSwitch)+1;
				if(temp < distance.get(dstSwitch)) {
					distance.put(dstSwitch, temp);
					result.put(dstSwitch, dstPort);
				}
			}
		}

		return result;
	}


	public void install_rules(HashMap<IOFSwitch, Integer> path, Host host) {
		Map<Long, IOFSwitch> switches = getSwitches();

		if(path == null) return;
		for(IOFSwitch curr_switch : switches.values()) {
			Integer port = path.get(curr_switch);
			if(port == null) continue;
			OFMatch match = new OFMatch();
			match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			match.setNetworkDestination(host.getIPv4Address());
			OFActionOutput action_output = new OFActionOutput(port);
			OFInstructionApplyActions apply_action = new OFInstructionApplyActions();
			ArrayList<OFAction> temp_list_1 = new ArrayList<OFAction>();
			temp_list_1.add(action_output);
			apply_action.setActions(temp_list_1);
			ArrayList<OFInstruction> temp_list_2 = new ArrayList<OFInstruction>();
			temp_list_2.add(apply_action);
			SwitchCommands.installRule(curr_switch, this.table, SwitchCommands.DEFAULT_PRIORITY, match, temp_list_2);
		}
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
		public void linkDiscoveryUpdate(LDUpdate update)
		{ this.linkDiscoveryUpdate(Arrays.asList(update)); }

	/**
	 * Event handler called when the IP address of a host changes.
	 * @param device information about the host
	 */
	@Override
		public void deviceIPV4AddrChanged(IDevice device)
		{ this.deviceAdded(device); }

	/**
	 * Event handler called when the VLAN of a host changes.
	 * @param device information about the host
	 */
	@Override
		public void deviceVlanChanged(IDevice device)
		{ /* Nothing we need to do, since we're not using VLANs */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
		public void switchActivated(long switchId)
		{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
		public void switchChanged(long switchId)
		{ /* Nothing we need to do */ }

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
		{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
		public String getName()
		{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
		public boolean isCallbackOrderingPrereq(String type, String name)
		{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
		public boolean isCallbackOrderingPostreq(String type, String name)
		{ return false; }

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
			Collection<Class<? extends IFloodlightService >> floodlightService = new ArrayList<Class<? extends IFloodlightService>>();
			floodlightService.add(IFloodlightProviderService.class);
			floodlightService.add(ILinkDiscoveryService.class);
			floodlightService.add(IDeviceService.class);
			return floodlightService;
		}
}
