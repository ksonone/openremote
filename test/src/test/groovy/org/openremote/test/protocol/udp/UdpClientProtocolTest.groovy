/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.test.protocol.udp

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandler
import io.netty.channel.socket.DatagramChannel
import io.netty.handler.codec.FixedLengthFrameDecoder
import io.netty.handler.codec.MessageToMessageEncoder
import io.netty.handler.codec.bytes.ByteArrayDecoder
import org.openremote.agent.protocol.ProtocolExecutorService
import org.openremote.agent.protocol.udp.AbstractUdpServer
import org.openremote.agent.protocol.udp.UdpClientProtocol
import org.openremote.agent.protocol.udp.UdpStringServer
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.model.Constants
import org.openremote.model.asset.Asset
import org.openremote.model.asset.AssetAttribute
import org.openremote.model.asset.AssetType
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.attribute.*
import org.openremote.model.value.Values
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static org.openremote.model.asset.agent.ProtocolConfiguration.initProtocolConfiguration

class UdpClientProtocolTest extends Specification implements ManagerContainerTrait {

    def "Check UDP client protocol configuration and linked attribute deployment"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 10, delay: 1)

        and: "the UDP client protocol min times are adjusted for testing"
        UdpClientProtocol.MIN_POLLING_MILLIS = 10

        when: "the container starts"
        def serverPort = findEphemeralPort()
        def container = startContainerNoDemoImport(defaultConfig(serverPort), defaultServices())
        def protocolExecutorService = container.getService(ProtocolExecutorService.class)
        def udpClientProtocol = container.getService(UdpClientProtocol.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def agentService = container.getService(AgentService.class)

        then: "the container should be running"
        conditions.eventually {
            assert container.isRunning()
        }

        when: "a simple UDP echo server is started"
        def echoServerPort = findEphemeralPort()
        def clientPort = findEphemeralPort()
        AbstractUdpServer echoServer = new UdpStringServer(protocolExecutorService, new InetSocketAddress(echoServerPort), ";", Integer.MAX_VALUE, true)
        def echoSkipCount = 0
        def clientActualPort = null
        def lastCommand = null
        def lastSend = null
        def receivedMessages = []
        echoServer.addMessageConsumer({
            message, channel, sender ->
                clientActualPort = sender.port
                lastCommand = message
                receivedMessages.add(message)

                if (echoSkipCount == 0) {
                    echoServer.sendMessage(message, sender)
                    lastSend = message
                } else {
                    echoSkipCount--
                }
        })
        echoServer.start()

        then: "the UDP echo server should be connected"
        conditions.eventually {
            echoServer.connectionStatus == ConnectionStatus.CONNECTED
        }

        when: "an agent with a UDP client protocol configuration is created"
        def agent = new Asset()
        agent.setRealm(Constants.MASTER_REALM)
        agent.setName("Test Agent")
        agent.setType(AssetType.AGENT)
        agent.setAttributes(
            initProtocolConfiguration(new AssetAttribute("protocolConfig"), UdpClientProtocol.PROTOCOL_NAME)
                .addMeta(
                    new MetaItem(
                        UdpClientProtocol.META_PROTOCOL_HOST,
                        Values.create("255.255.255.255")
                    ),
                    new MetaItem(
                        UdpClientProtocol.META_PROTOCOL_PORT,
                        Values.create(echoServerPort)
                    ),
                    new MetaItem(
                        UdpClientProtocol.META_PROTOCOL_BIND_PORT,
                        Values.create(clientPort)
                    ),
                    new MetaItem(
                        UdpClientProtocol.META_RESPONSE_TIMEOUT_MILLIS,
                        Values.create(200)
                    )
                )
        )

        and: "the agent is added to the asset service"
        agent = assetStorageService.merge(agent)

        then: "the protocol should authenticate and start pinging the server and the connection status should become CONNECTED"
        conditions.eventually {
            def status = agentService.getProtocolConnectionStatus(new AttributeRef(agent.id, "protocolConfig"))
            assert status == ConnectionStatus.CONNECTED
        }

        when: "an asset is created with attributes linked to the protocol configuration"
        def asset = new Asset("Test Asset", AssetType.THING, agent)
        asset.setAttributes(
            // simple send with no retries
            new AssetAttribute("echoHello", AttributeValueType.STRING)
                .addMeta(
                    new MetaItem(MetaItemType.AGENT_LINK, new AttributeRef(agent.id, "protocolConfig").toArrayValue()),
                    new MetaItem(UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE, Values.create('Hello {$value};')),
                    new MetaItem(UdpClientProtocol.META_SEND_RETRIES, Values.create(0)),
                    new MetaItem(UdpClientProtocol.META_SERVER_ALWAYS_RESPONDS, Values.create(true)),
                    new MetaItem(MetaItemType.EXECUTABLE)
                ),
            // attribute send with 10 retries
            new AssetAttribute("echoWorld", AttributeValueType.STRING)
                .addMeta(
                    new MetaItem(MetaItemType.AGENT_LINK, new AttributeRef(agent.id, "protocolConfig").toArrayValue()),
                    new MetaItem(UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE, Values.create("World;")),
                    new MetaItem(UdpClientProtocol.META_SEND_RETRIES, Values.create(10)),
                    new MetaItem(UdpClientProtocol.META_SERVER_ALWAYS_RESPONDS, Values.create(true)),
                    new MetaItem(MetaItemType.EXECUTABLE)
                ),
            // attribute poll with 3 retries
            new AssetAttribute("pollTest", AttributeValueType.STRING)
                .addMeta(
                    new MetaItem(MetaItemType.AGENT_LINK, new AttributeRef(agent.id, "protocolConfig").toArrayValue()),
                    new MetaItem(UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE, Values.create("Test;")),
                    new MetaItem(UdpClientProtocol.META_POLLING_MILLIS, Values.create(100)),
                    new MetaItem(UdpClientProtocol.META_SEND_RETRIES, Values.create(3))
                ),
        )

        and: "the asset is merged into the asset service"
        asset = assetStorageService.merge(asset)

        then: "the polling attribute should have the correct values"
        def messageCount = 0
        conditions.eventually {
            asset = assetStorageService.find(asset.getId(), true)
            assert asset.getAttribute("pollTest").flatMap({ it.getValueAsString() }).orElse(null) == "Test"
            messageCount = receivedMessages.size()
        }

        and: "the attribute should continue sending to the server"
        conditions.eventually {
            receivedMessages.size() > messageCount + 1
        }

        when: "a linked attribute value is updated"
        def attributeEvent = new AttributeEvent(asset.id,
            "echoHello",
            Values.create("there"))
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "the server should have received the request"
        conditions.eventually {
            assert receivedMessages.indexOf("Hello there") >= 0
        }

        when: "the protocol configuration is disabled"
        agent.getAttribute("protocolConfig").ifPresent{it.addMeta(MetaItemType.DISABLED)}
        agent = assetStorageService.merge(agent)

        then: "the protocol should be unlinked"
        conditions.eventually {
            assert udpClientProtocol.clientMap.isEmpty()
            assert udpClientProtocol.attributeInfoMap.isEmpty()
        }

        when: "the received messages are cleared"
        receivedMessages.clear()

        then: "after a while no more messages should be received by the server"
        new PollingConditions(initialDelay: 1, timeout: 2).eventually {
            assert receivedMessages.isEmpty()
        }

        when: "the polling attribute is removed"
        asset.removeAttribute("pollTest")
        asset = assetStorageService.merge(asset)

        and: "the protocol configuration is re-enabled"
        agent.getAttribute("protocolConfig").ifPresent{it.meta.removeIf{it.name.orElse(null) == MetaItemType.DISABLED.urn}}
        agent = assetStorageService.merge(agent)

        then: "the two remaining attributes should be linked"
        conditions.eventually {
            assert udpClientProtocol.clientMap.size() == 1
            assert udpClientProtocol.attributeInfoMap.size() == 2
        }

        when: "the server stops responding to the next request"
        echoSkipCount = 1
        lastSend = null

        and: "the attribute that is configured for no retries is executed"
        attributeEvent = new AttributeEvent(asset.id,
            "echoHello",
            AttributeExecuteStatus.REQUEST_START.asValue())
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "the server should have received the attribute write value but it doesn't reply"
        conditions.eventually {
            assert receivedMessages.size() == 1
            assert lastSend == null
        }

        then: "after a while no more messages should be received by the server and the send queue is clear"
        new PollingConditions(initialDelay: 1, timeout: 2).eventually {
            assert receivedMessages.size() == 1
            assert udpClientProtocol.clientMap.get(new AttributeRef(agent.id, "protocolConfig")).actionQueue.isEmpty()
        }

        when: "the server stops responding to the next 3 requests"
        echoSkipCount = 3
        lastSend = null
        receivedMessages.clear()

        and: "the attribute that is configured for retries is executed"
        attributeEvent = new AttributeEvent(asset.id,
            "echoWorld",
            AttributeExecuteStatus.REQUEST_START.asValue())
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "the server should have received the attribute write value 4 times and responded on the 4th"
        conditions.eventually {
            assert receivedMessages.size() == 4
            assert lastSend == "World"
        }

        then: "after a while no more messages should be received by the server"
        new PollingConditions(initialDelay: 1, timeout: 2).eventually {
            assert receivedMessages.size() == 4
        }

        when: "the echo server is changed to a byte based server"
        echoServer.stop()
        echoServer.removeAllMessageConsumers()
        echoServer = new AbstractUdpServer<byte[]>(protocolExecutorService, new InetSocketAddress(echoServerPort)) {

            @Override
            protected void addDecoders(DatagramChannel channel) {
                addDecoder(channel, new FixedLengthFrameDecoder(3))
                addDecoder(channel, new ByteArrayDecoder())
            }

            @Override
            protected void addEncoders(DatagramChannel channel) {
                addEncoder(channel, new MessageToMessageEncoder<byte[]>() {

                    @Override
                    protected void encode(ChannelHandlerContext channelHandlerContext, byte[] bytes, List<Object> out) throws Exception {
                        out.add(Unpooled.copiedBuffer(bytes))
                    }
                })
            }
        }
        byte[] lastBytes = null
        echoServer.addMessageConsumer({
            message, channel, sender ->
                lastBytes = message
        })
        echoServer.start()

        then: "the server should be connected"
        conditions.eventually {
            echoServer.connectionStatus == ConnectionStatus.CONNECTED
        }

        when: "the protocol configuration is updated to use HEX mode"
        def client = udpClientProtocol.clientMap.get(new AttributeRef(agent.id, "protocolConfig"))
        agent.getAttribute("protocolConfig").ifPresent{it.addMeta(UdpClientProtocol.META_PROTOCOL_CONVERT_HEX)}
        agent = assetStorageService.merge(agent)

        then: "the protocol should be relinked"
        conditions.eventually {
            assert udpClientProtocol.clientMap.size() == 1
            assert udpClientProtocol.clientMap.get(new AttributeRef(agent.id, "protocolConfig")) != client
        }

        when: "the linked attributes are also updated to work with hex server"
        def attributeInfo = udpClientProtocol.attributeInfoMap.get(new AttributeRef(asset.id, "echoHello"))
        asset.getAttribute("echoHello").ifPresent({it.meta.replaceAll{it.name.get() == UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE.urn ? new MetaItem(UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE, Values.create("abcdef")) : it}})
        asset.getAttribute("echoWorld").ifPresent({it.meta.replaceAll{it.name.get() == UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE.urn ? new MetaItem(UdpClientProtocol.META_ATTRIBUTE_WRITE_VALUE, Values.create("123456")) : it}})
        asset = assetStorageService.merge(asset)

        then: "the attributes should be relinked"
        conditions.eventually {
            assert udpClientProtocol.attributeInfoMap.size() == 2
            assert udpClientProtocol.attributeInfoMap.get(new AttributeRef(asset.id, "echoHello")) != attributeInfo
        }

        when: "the hello linked attribute is executed"
        attributeEvent = new AttributeEvent(asset.id,
            "echoHello",
            AttributeExecuteStatus.REQUEST_START.asValue())
        assetProcessingService.sendAttributeEvent(attributeEvent)

        then: "the bytes should be received by the server"
        conditions.eventually {
            assert lastBytes != null
            assert lastBytes.length == 3
            assert (lastBytes[0] & 0xFF) == 171
            assert (lastBytes[1] & 0xFF) == 205
            assert (lastBytes[2] & 0xFF) == 239
        }

        cleanup: "the server should be stopped"
        echoServer.stop()
        stopContainer(container)
    }
}