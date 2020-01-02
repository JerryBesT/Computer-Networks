all: comp

comp:
	mkdir ./compile
	javac -d ./compile src/edu/wisc/cs/sdn/simpledns/SimpleDNS.java src/edu/wisc/cs/sdn/simpledns/packet/*.java

clean:
	rm -rf compile

run: 
	cd compile && java edu.wisc.cs.sdn.simpledns.SimpleDNS -r 198.41.0.4 -e ../ec2.csv 
