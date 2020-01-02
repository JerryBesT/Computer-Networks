import enum
import logging
import llp
import queue
import struct
import threading
from threading import Timer
from threading import RLock


class SWPType(enum.IntEnum):
	DATA = ord('D')
	ACK = ord('A')

class SWPPacket:
	_PACK_FORMAT = '!BI'
	_HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
	MAX_DATA_SIZE = 1400 # Leaves plenty of space for IP + UDP + SWP header 

	def __init__(self, type, seq_num, data=b''):
		self._type = type
		self._seq_num = seq_num
		self._data = data

	@property
	def type(self):
		return self._type

	@property
	def seq_num(self):
		return self._seq_num

	@property
	def data(self):
		return self._data

	def to_bytes(self):
		header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value, self._seq_num)
		return header + self._data
	 
	@classmethod
	def from_bytes(cls, raw):
		header = struct.unpack(SWPPacket._PACK_FORMAT, raw[:SWPPacket._HEADER_SIZE])
		type = SWPType(header[0])
		seq_num = header[1]
		data = raw[SWPPacket._HEADER_SIZE:]
		return SWPPacket(type, seq_num, data)

	def __str__(self):
		return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))

class SWPSender:
	_SEND_WINDOW_SIZE = 5
	_TIMEOUT = 1

	def __init__(self, remote_address, loss_probability=0):
		self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address, loss_probability=loss_probability)

		# Start receive thread
		self._recv_thread = threading.Thread(target=self._recv)
		self._recv_thread.start()

		self.sema = threading.BoundedSemaphore(value=self._SEND_WINDOW_SIZE)
		
		# last acked received
		self.LAR = 0
		# last frame sent
		self.LFS = 0

		# outstanding packets
		self.sendQ = [None] * 5

		# mapping: key-packet, value-time
		self.timing = {}
		
		self.lock = RLock()

	def send(self, data):
		for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
			self._send(data[i:i+SWPPacket.MAX_DATA_SIZE])

	def _retransmit(self, seq_num):
		
		pkt = self.sendQ[(seq_num - 1) % SWPSender._SEND_WINDOW_SIZE]
		t = Timer(SWPSender._TIMEOUT, self._retransmit, [seq_num])
		self.timing[(seq_num-1)%SWPSender._SEND_WINDOW_SIZE] = t	
		t.start()
		self._llp_endpoint.send(pkt.to_bytes())
		return

	def _send(self, data):
		
		# 1. Wait for a free space in the send window—a semaphore is the simplest way to handle this.
		self.sema.acquire()

		self.LFS = self.LFS + 1
		# 2. Assign the chunk of data a sequence number—the first chunk of data is assigned sequence number 0, 
		# and the sequence number is incremented for each subsequent chunk of data.

		pkt = SWPPacket(SWPType.DATA, self.LFS, data)
		# 3.Add the chunk of data to a buffer—in case it needs to be retransmitted.
		# logging.debug("%d" %  ((pkt.seq_num - 1) % SWPSender._SEND_WINDOW_SIZE))
		self.sendQ[(pkt.seq_num - 1) % SWPSender._SEND_WINDOW_SIZE] = pkt

		# 4. Send the data in an SWP packet with the appropriate type (D) and 
		# sequence number—use the SWPPacket class to construct such a packet and 
		# use the send method provided by the LLPEndpoint class to transmit the packet across the network.)
		
		
		# 5. Start a retransmission timer—the Timer class provides a convenient way to do this; 
		# the timeout should be 1 second, defined by the constant SWPSender._TIMEOUT; 
		# when the timer expires, the _retransmit method should be called.
		self.lock.acquire();
		t = Timer(SWPSender._TIMEOUT, self._retransmit, [pkt.seq_num]) # check
		self.timing[(pkt.seq_num-1) % SWPSender._SEND_WINDOW_SIZE] = t
	  	
		t.start()
		self._llp_endpoint.send(pkt.to_bytes())
		self.lock.release();
		return

	def in_window(self, seq_num, lar, lfs):
		pos = seq_num - lar
		maxPos = lfs - lar + 1
		return pos < maxPos

	def _recv(self):
		while True:
			# Receive SWP packet
			raw = self._llp_endpoint.recv()
			if raw is None:
				continue
			packet = SWPPacket.from_bytes(raw)
			
			
			logging.debug("Received: %s" % packet)
			
			
			if packet.type.value != SWPType.ACK.value:
				continue

			seq_num = packet.seq_num
			if(seq_num == 0):
				continue
			
			if self.in_window(seq_num, self.LAR + 1, self.LFS) and (self.LAR < seq_num):
				self.lock.acquire();
				
				
				timer = self.timing[(seq_num-1) % SWPSender._SEND_WINDOW_SIZE]
				self.lock.acquire();
				
				timer.cancel()
				self.lock.release();

				self.LAR = self.LAR + 1
				self.sema.release()
				self.lock.release();
				
				while self.LAR < seq_num:
					
					
					# cancel the retrans. for this data
					self.lock.acquire()
					pkt = self.sendQ[(self.LAR-1) % SWPSender._SEND_WINDOW_SIZE]
					timer = self.timing[(pkt.seq_num-1) % SWPSender._SEND_WINDOW_SIZE]

					self.lock.acquire();
					timer.cancel()
					self.lock.release()

					self.LAR = self.LAR + 1
					self.sema.release()
					self.lock.release()
			for i in range(0,5):
				p = self.sendQ[i]
				if p is None:
					continue
				if p.seq_num < self.LAR:
					self.timing[i].cancel()
		return

class SWPReceiver:
	_RECV_WINDOW_SIZE = 5

	def __init__(self, local_address, loss_probability=0):
		self._llp_endpoint = llp.LLPEndpoint(local_address=local_address, loss_probability=loss_probability)

		# Received data waiting for application to consume
		self._ready_data = queue.Queue()

		# Start receive thread
		self._recv_thread = threading.Thread(target=self._recv)
		self._recv_thread.start()
		

		# next frame(packet) expected
		self.NFE = 0

		# possible "hole" packets in the queue to be acked
		self.recvQ = [None]*5
		self.sema = threading.BoundedSemaphore(value=self._RECV_WINDOW_SIZE)

	def recv(self):
		return self._ready_data.get()

	def _recv(self):
		while True:
			# Receive data packet
			raw = self._llp_endpoint.recv()
			packet = SWPPacket.from_bytes(raw)
			logging.debug("Received: %s" % packet)
			
			self.sema.acquire()
			t = packet.type
			seq_num = packet.seq_num

			# if not data, simply ignore
			if(t.value != SWPType.DATA.value):
				continue

			# if already acked
			if(self.NFE >= seq_num):
				#resend the ACK
				pkt = SWPPacket(SWPType.ACK, self.NFE, packet.data)
				self._llp_endpoint.send(pkt.to_bytes())
				self.sema.release()
				continue

			# add to buffer
			
			temp_pkt = self.recvQ[(seq_num-1) % 5]
			if(temp_pkt != None):
				temp_seq = temp_pkt.seq_num
				if(seq_num == temp_seq):
					self.sema.release()
					continue
			
			self.recvQ[(seq_num-1) % self._RECV_WINDOW_SIZE] = packet
			temp = self.NFE
			# traverse the buffer
			for index in range(0,5):
				
				curr_pkt = self.recvQ[(temp+index) % self._RECV_WINDOW_SIZE]
				
				if curr_pkt == None:
					break;
				if curr_pkt.seq_num == self.NFE+1:
					self._ready_data.put(curr_pkt.data)
					self.NFE = self.NFE + 1
					self.sema.release()
				else:
					break;

			ack_pkt = SWPPacket(SWPType.ACK, self.NFE, packet.data)
			self._llp_endpoint.send(ack_pkt.to_bytes())

		return
