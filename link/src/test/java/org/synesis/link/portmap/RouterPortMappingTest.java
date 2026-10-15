package org.synesis.link.portmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the UPnP conversation with a fake router on the loopback interface. */
final class RouterPortMappingTest {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private HttpServer router;
  private final List<String> actions = new CopyOnWriteArrayList<>();
  private volatile String externalAddress = "203.0.113.5";

  @BeforeEach
  void startRouter() throws IOException {
    router = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
    router.createContext("/desc.xml", exchange -> reply(exchange, 200, """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceList><device><serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>
        <controlURL>/common</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
        <controlURL>/ctl/IPConn</controlURL></service>
        </serviceList></device></deviceList></device></root>"""));
    router.createContext("/ctl/IPConn", exchange -> {
      String action = exchange.getRequestHeaders().getFirst("SOAPAction");
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      actions.add(action + " " + body.replaceAll(".*<s:Body>|</s:Body>.*", ""));
      String name = action.substring(action.indexOf('#') + 1, action.length() - 1);
      reply(exchange, 200, "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
          + "<s:Body><u:" + name + "Response xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">"
          + (name.equals("GetExternalIPAddress")
          ? "<NewExternalIPAddress>" + externalAddress + "</NewExternalIPAddress>" : "")
          + "</u:" + name + "Response></s:Body></s:Envelope>");
    });
    router.start();
  }

  @AfterEach
  void stopRouter() {
    router.stop(0);
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private URI description() {
    return URI.create("http://" + LOOPBACK.getHostAddress() + ":" + router.getAddress().getPort()
        + "/desc.xml");
  }

  @Test
  void opensAForwardOfTheSamePortAndRemovesItOnClose() throws Exception {
    UpnpGateway gateway = UpnpGateway.describe(description(), LOOPBACK, TIMEOUT).orElseThrow();

    try (RouterPortMapping mapping = RouterPortMapping.open(gateway, 45_000)) {
      assertEquals(new InetSocketAddress("203.0.113.5", 45_000), mapping.external());
      String add = actions.get(1);
      assertTrue(add.startsWith("\"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\""), add);
      assertTrue(add.contains("<NewExternalPort>45000</NewExternalPort><NewProtocol>UDP</NewProtocol>"
          + "<NewInternalPort>45000</NewInternalPort>"), add);
      assertTrue(add.contains("<NewLeaseDuration>3600</NewLeaseDuration>"), add);
    }
    assertTrue(actions.getLast().contains("#DeletePortMapping")
        && actions.getLast().contains("<NewExternalPort>45000</NewExternalPort>"), actions.getLast());
  }

  @Test
  void refusesToForwardWhenTheRoutersOwnAddressIsNotPublic() throws Exception {
    UpnpGateway gateway = UpnpGateway.describe(description(), LOOPBACK, TIMEOUT).orElseThrow();
    for (String behindAnotherNat : List.of("192.168.0.2", "100.72.1.9", "10.1.2.3")) {
      actions.clear();
      externalAddress = behindAnotherNat;
      assertThrows(IOException.class, () -> RouterPortMapping.open(gateway, 45_000));
      assertFalse(actions.stream().anyMatch(action -> action.contains("#AddPortMapping")));
    }
  }

  @Test
  void followsOnlyADescriptionOnTheHostThatAnsweredTheSearch() throws Exception {
    String reply = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n"
        + "LOCATION: " + description() + "\r\n\r\n";
    assertEquals(description(), UpnpGateway.location(reply, LOOPBACK).orElseThrow().location());
    assertTrue(UpnpGateway.location(reply, InetAddress.getByName("192.0.2.1")).isEmpty());
    assertTrue(UpnpGateway.location(reply.replace("http://", "file://"), LOOPBACK).isEmpty());
  }

  @Test
  void rejectsADescriptionWithADocumentTypeDeclaration() {
    byte[] entity = ("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
        + "<r>&x;</r>").getBytes(StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> UpnpGateway.parse(entity));
  }

  @Test
  void onlyAnAddressReachableFromTheInternetIsPublic() throws Exception {
    for (String address : List.of("203.0.113.5", "8.8.8.8")) {
      assertTrue(RouterPortMapping.isPublic(InetAddress.getByName(address)), address);
    }
    for (String address : List.of("10.0.0.1", "172.16.4.4", "192.168.1.1", "100.64.0.1",
        "127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1")) {
      assertFalse(RouterPortMapping.isPublic(InetAddress.getByName(address)), address);
    }
  }
}
