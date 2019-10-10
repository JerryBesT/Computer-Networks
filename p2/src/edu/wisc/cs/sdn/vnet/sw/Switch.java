package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import java.util.*;
import net.floodlightcontroller.packet.MACAddress;


/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{

	public Map<MACAddress,Iface> table;
	public Map<MACAddress,Long> time;
	public TimeThread check_timeout;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.table = new HashMap<MACAddress,Iface>();
		this.time = new HashMap<MACAddress,Long>();
		this.check_timeout = new TimeThread(table, time);
		this.check_timeout.start();
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		MACAddress srcMAC = etherPacket.getSourceMAC();
		MACAddress dstMAC = etherPacket.getDestinationMAC();

		//learn
		synchronized(this.table) {
			table.put(srcMAC, inIface);
		}
		synchronized(this.time) {
			time.put(srcMAC, (Long)System.currentTimeMillis());
		}

		//send
		if(table.containsKey(dstMAC)) { //we know the interface for dst
			Iface outIface = table.get(dstMAC);//get interface for dst
			boolean result = this.sendPacket(etherPacket, outIface);
		} else { //broadcasting
			// Getting an iterator
			Iterator mapIt = this.interfaces.entrySet().iterator();

			// Iterate through the hashmap
			while (mapIt.hasNext()) {
				Map.Entry mapElement = (Map.Entry)mapIt.next();
				Iface outIface = (Iface)mapElement.getValue();
				if(outIface == inIface) continue;
				boolean result = this.sendPacket(etherPacket, outIface);
			}
		}
	}

	class TimeThread extends Thread
	{
		Map<MACAddress, Iface> table;
		Map<MACAddress, Long> time;
		public TimeThread(Map<MACAddress, Iface> table, Map<MACAddress, Long> time) {
			this.table = table;
			this.time = time;
		}

		public void run() {
			while(true) {
				//update time
				if(this.time != null && this.time.entrySet() != null) {

					synchronized(this.time) {
						// Iterate through the hashmap
						Iterator timeIt = this.time.entrySet().iterator();
						while (timeIt.hasNext()) {
							Map.Entry mapElement = (Map.Entry)timeIt.next();
							long setupTime = (long)mapElement.getValue();
							long currTime = System.currentTimeMillis();
							if(setupTime + 15000 < currTime) {
								synchronized(this.table) {
									table.remove(mapElement.getKey());
								}
								timeIt.remove();
							}
						}
					}
				}
			}

		}
	}

}
