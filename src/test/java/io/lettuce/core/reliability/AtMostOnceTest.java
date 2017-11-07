/*
 * Copyright 2011-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.reliability;

import static com.google.code.tempusfugit.temporal.Duration.millis;
import static io.lettuce.ConnectionTestUtil.getCommandBuffer;
import static io.lettuce.ConnectionTestUtil.getStack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import io.lettuce.ConnectionTestUtil;
import io.lettuce.Delay;
import io.lettuce.Wait;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.Utf8StringCodec;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.EncoderException;
import io.netty.util.Version;

/**
 * @author Mark Paluch
 */
@SuppressWarnings("rawtypes")
public class AtMostOnceTest extends AbstractRedisClientTest {

    protected final Utf8StringCodec CODEC = new Utf8StringCodec();
    protected String key = "key";

    @Before
    public void before() {
        client.setOptions(ClientOptions.builder().autoReconnect(false).build());

        // needs to be increased on slow systems...perhaps...
        client.setDefaultTimeout(3, TimeUnit.SECONDS);

        RedisCommands<String, String> connection = client.connect().sync();
        connection.flushall();
        connection.flushdb();
        connection.getStatefulConnection().close();
    }

    @Test
    public void connectionIsConnectedAfterConnect() {

        StatefulRedisConnection<String, String> connection = client.connect();

        assertThat(ConnectionTestUtil.getConnectionState(connection));

        connection.close();
    }

    @Test
    public void noReconnectHandler() {

        StatefulRedisConnection<String, String> connection = client.connect();

        assertThat(ConnectionTestUtil.getConnectionWatchdog(connection)).isNull();

        connection.close();
    }

    @Test
    public void basicOperations() {

        RedisCommands<String, String> connection = client.connect().sync();

        connection.set(key, "1");
        assertThat(connection.get("key")).isEqualTo("1");

        connection.getStatefulConnection().close();
    }

    @Test
    public void noBufferedCommandsAfterExecute() {

        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> sync = connection.sync();

        sync.set(key, "1");

        assertThat(getStack(connection)).isEmpty();
        assertThat(getCommandBuffer(connection)).isEmpty();

        connection.close();
    }

    @Test
    public void commandIsExecutedOnce() {

        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> sync = connection.sync();

        sync.set(key, "1");
        sync.incr(key);
        assertThat(sync.get(key)).isEqualTo("2");

        sync.incr(key);
        assertThat(sync.get(key)).isEqualTo("3");

        sync.incr(key);
        assertThat(sync.get(key)).isEqualTo("4");

        connection.close();
    }

    @Test
    public void commandNotExecutedFailsOnEncode() {

        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> sync = client.connect().sync();
        RedisChannelWriter channelWriter = ConnectionTestUtil.getChannelWriter(connection);

        sync.set(key, "1");
        AsyncCommand<String, String, String> working = new AsyncCommand<>(new Command<String, String, String>(CommandType.INCR,
                new IntegerOutput(CODEC), new CommandArgs<String, String>(CODEC).addKey(key)));
        channelWriter.write(working);
        assertThat(working.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(sync.get(key)).isEqualTo("2");

        AsyncCommand<String, String, Object> command = new AsyncCommand<String, String, Object>(
                new Command<String, String, Object>(CommandType.INCR, new IntegerOutput(CODEC),
                        new CommandArgs<String, String>(CODEC).addKey(key))) {

            @Override
            public void encode(ByteBuf buf) {
                throw new IllegalStateException("I want to break free");
            }
        };

        channelWriter.write(command);

        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(getException(command)).isInstanceOf(EncoderException.class);

        Wait.untilTrue(() -> !ConnectionTestUtil.getStack(connection).isEmpty()).waitOrTimeout();

        assertThat(ConnectionTestUtil.getStack(connection)).isNotEmpty();
        ConnectionTestUtil.getStack(connection).clear();

        assertThat(sync.get(key)).isEqualTo("2");

        assertThat(ConnectionTestUtil.getStack(connection)).isEmpty();
        assertThat(ConnectionTestUtil.getCommandBuffer(connection)).isEmpty();

        connection.close();
    }

    @Test
    public void commandNotExecutedChannelClosesWhileFlush() {

        assumeTrue(Version.identify().get("netty-transport").artifactVersion().startsWith("4.0.2"));

        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> sync = connection.sync();
        RedisCommands<String, String> verificationConnection = client.connect().sync();
        RedisChannelWriter channelWriter = ConnectionTestUtil.getChannelWriter(connection);

        sync.set(key, "1");
        assertThat(verificationConnection.get(key)).isEqualTo("1");

        final CountDownLatch block = new CountDownLatch(1);

        AsyncCommand<String, String, Object> command = new AsyncCommand<String, String, Object>(new Command<>(CommandType.INCR,
                new IntegerOutput(CODEC), new CommandArgs<>(CODEC).addKey(key))) {

            @Override
            public void encode(ByteBuf buf) {
                try {
                    block.await();
                } catch (InterruptedException e) {
                }
                super.encode(buf);
            }
        };

        channelWriter.write(command);

        Channel channel = ConnectionTestUtil.getChannel(connection);
        channel.unsafe().disconnect(channel.newPromise());

        assertThat(channel.isOpen()).isFalse();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isFalse();
        block.countDown();
        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isTrue();

        assertThat(verificationConnection.get(key)).isEqualTo("1");

        assertThat(getStack(connection)).isEmpty();
        assertThat(getCommandBuffer(connection)).isEmpty();

        connection.close();
    }

    @Test
    public void commandFailsDuringDecode() {

        StatefulRedisConnection<String, String> connection = client.connect();
        RedisCommands<String, String> sync = connection.sync();
        RedisChannelWriter channelWriter = ConnectionTestUtil.getChannelWriter(connection);
        RedisCommands<String, String> verificationConnection = client.connect().sync();

        sync.set(key, "1");

        AsyncCommand<String, String, String> command = new AsyncCommand<>(new Command<>(CommandType.INCR, new StatusOutput<>(
                CODEC), new CommandArgs<>(CODEC).addKey(key)));

        channelWriter.write(command);

        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(getException(command)).isInstanceOf(IllegalStateException.class);

        assertThat(verificationConnection.get(key)).isEqualTo("2");
        assertThat(sync.get(key)).isEqualTo("2");

        connection.close();
    }

    @Test
    public void noCommandsExecutedAfterConnectionIsDisconnected() {

        StatefulRedisConnection<String, String> connection = client.connect();
        connection.sync().quit();

        Wait.untilTrue(() -> !connection.isOpen()).waitOrTimeout();

        try {
            connection.sync().incr(key);
        } catch (RedisException e) {
            assertThat(e).isInstanceOf(RedisException.class);
        }

        connection.close();

        StatefulRedisConnection<String, String> connection2 = client.connect();
        connection2.async().quit();
        Delay.delay(millis(100));

        try {

            Wait.untilTrue(() -> !connection.isOpen()).waitOrTimeout();

            connection2.sync().incr(key);
        } catch (Exception e) {
            assertThat(e).isExactlyInstanceOf(RedisException.class).hasMessageContaining("not connected");
        }

        connection2.close();
    }

    private Throwable getException(RedisFuture<?> command) {
        try {
            command.get();
        } catch (InterruptedException e) {
            return e;
        } catch (ExecutionException e) {
            return e.getCause();
        }
        return null;
    }
}