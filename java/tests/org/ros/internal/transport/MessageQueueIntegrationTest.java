/*
 * Copyright (C) 2011 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.ros.internal.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.HeapChannelBufferFactory;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.junit.Before;
import org.junit.Test;
import org.ros.internal.node.service.ServiceManager;
import org.ros.internal.node.topic.TopicManager;
import org.ros.internal.transport.tcp.TcpClientPipelineFactory;
import org.ros.internal.transport.tcp.TcpServerPipelineFactory;
import org.ros.message.Message;
import org.ros.message.MessageDeserializer;
import org.ros.message.MessageSerializer;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;

/**
 * @author damonkohler@google.com (Damon Kohler)
 */
public class MessageQueueIntegrationTest {

  private OutgoingMessageQueue<Message> out;

  private class ServerHandler extends SimpleChannelHandler {
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
      Channel channel = e.getChannel();
      out.addChannel(channel);
    }
  }

  @Before
  public void setup() {
    out = new OutgoingMessageQueue<Message>(new MessageSerializer<Message>());
    out.start();
  }

  @Test
  public void testStartFailsIfRunning() {
    try {
      out.start();
      fail();
    } catch (RuntimeException e) {
      // start() must fail if the queue is already running.
    }
  }

  @Test
  public void testStartFailsAfterShutdown() {
    out.shutdown();
    try {
      out.start();
      fail();
    } catch (RuntimeException e) {
      // start() must fail if the queue has been started and stopped once
      // already.
    }
  }

  @Test
  public void testSendAndReceiveMessage() throws InterruptedException {
    Channel serverChannel = buildServerChannel();

    IncomingMessageQueue<org.ros.message.std_msgs.String> firstIncomingQueue =
        new IncomingMessageQueue<org.ros.message.std_msgs.String>(
            new MessageDeserializer<org.ros.message.std_msgs.String>(
                org.ros.message.std_msgs.String.class));

    connectIncomingMessageQueue(firstIncomingQueue, serverChannel);

    IncomingMessageQueue<org.ros.message.std_msgs.String> secondIncomingQueue =
        new IncomingMessageQueue<org.ros.message.std_msgs.String>(
            new MessageDeserializer<org.ros.message.std_msgs.String>(
                org.ros.message.std_msgs.String.class));

    connectIncomingMessageQueue(secondIncomingQueue, serverChannel);

    // TODO(damonkohler): Ugly hack because we can't yet determine when the
    // connection handshake has completed.
    Thread.sleep(100);

    org.ros.message.std_msgs.String hello = new org.ros.message.std_msgs.String();
    hello.data = "Would you like to play a game?";
    out.put(hello);
    assertEquals(firstIncomingQueue.take(), hello);
    assertEquals(secondIncomingQueue.take(), hello);
  }

  private void connectIncomingMessageQueue(
      final IncomingMessageQueue<org.ros.message.std_msgs.String> in, Channel serverChannel) {
    ChannelFactory clientChannelFactory =
        new NioClientSocketChannelFactory(Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    ClientBootstrap clientBootstrap = new ClientBootstrap(clientChannelFactory);
    clientBootstrap.setOption("bufferFactory",
        new HeapChannelBufferFactory(ByteOrder.LITTLE_ENDIAN));
    TcpClientPipelineFactory clientPipelineFactory = new TcpClientPipelineFactory() {
      @Override
      public ChannelPipeline getPipeline() {
        ChannelPipeline pipeline = super.getPipeline();
        pipeline.addLast("ClientHandler", in.createChannelHandler());
        return pipeline;
      }
    };
    clientBootstrap.setPipelineFactory(clientPipelineFactory);
    clientBootstrap.connect(serverChannel.getLocalAddress()).awaitUninterruptibly();
  }

  private Channel buildServerChannel() {
    ChannelGroup serverChannelGroup = new DefaultChannelGroup();
    TopicManager topicManager = new TopicManager();
    ServiceManager serviceManager = new ServiceManager();
    NioServerSocketChannelFactory channelFactory =
        new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    ServerBootstrap bootstrap = new ServerBootstrap(channelFactory);
    bootstrap.setOption("child.bufferFactory",
        new HeapChannelBufferFactory(ByteOrder.LITTLE_ENDIAN));
    TcpServerPipelineFactory serverPipelineFactory =
        new TcpServerPipelineFactory(serverChannelGroup, topicManager, serviceManager) {
          @Override
          public ChannelPipeline getPipeline() {
            ChannelPipeline pipeline = super.getPipeline();
            pipeline.remove(TcpServerPipelineFactory.HANDSHAKE_HANDLER);
            pipeline.addLast("ServerHandler", new ServerHandler());
            return pipeline;
          }
        };
    bootstrap.setPipelineFactory(serverPipelineFactory);
    Channel serverChannel = bootstrap.bind(new InetSocketAddress(0));
    return serverChannel;
  }
}