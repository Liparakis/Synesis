package org.synesis.link.portmap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The home router's UPnP Internet Gateway Device service that can forward a public port.
 *
 * <p>Everything the router says is untrusted input from the local network: description and reply
 * sizes are bounded, XML is parsed without DTDs or external entities, and the description is only
 * fetched from the address that answered the search.
 */
final class UpnpGateway {

  private static final InetSocketAddress SSDP = new InetSocketAddress("239.255.255.250", 1900);
  private static final List<String> CONNECTION_SERVICES = List.of(
      "urn:schemas-upnp-org:service:WANIPConnection:2",
      "urn:schemas-upnp-org:service:WANIPConnection:1",
      "urn:schemas-upnp-org:service:WANPPPConnection:1");
  private static final int MAX_REPLY_BYTES = 64 * 1024;

  private final URI controlUrl;
  private final String serviceType;
  private final InetAddress router;
  private final HttpClient http;
  private final Duration timeout;

  UpnpGateway(URI controlUrl, String serviceType, InetAddress router, Duration timeout) {
    this.controlUrl = controlUrl;
    this.serviceType = serviceType;
    this.router = router;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /**
   * Searches the local network for a gateway and reads its description.
   *
   * @param timeout bound for the search and for each request
   * @return the gateway, or empty when no router answers with a usable service
   */
  static Optional<UpnpGateway> discover(Duration timeout) throws IOException {
    for (Located located : search(timeout)) {
      Optional<UpnpGateway> gateway = describe(located.location(), located.router(), timeout);
      if (gateway.isPresent()) {
        return gateway;
      }
    }
    return Optional.empty();
  }

  /** The address that answered a search and the description it pointed to. */
  record Located(URI location, InetAddress router) {
  }

  private static List<Located> search(Duration timeout) throws IOException {
    String request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
        + "MX: 1\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
    List<Located> found = new ArrayList<>();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout((int) timeout.toMillis());
      byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
      socket.send(new DatagramPacket(bytes, bytes.length, SSDP));
      byte[] buffer = new byte[2048];
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline && found.size() < 4) {
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        try {
          socket.receive(reply);
        } catch (SocketTimeoutException quiet) {
          break;
        }
        location(new String(buffer, 0, reply.getLength(), StandardCharsets.US_ASCII),
            reply.getAddress()).ifPresent(found::add);
      }
    }
    return found;
  }

  /**
   * Reads the LOCATION header of a search reply. A location on another host than the one that
   * replied is ignored, so a reply cannot send this machine to fetch an arbitrary address.
   */
  static Optional<Located> location(String reply, InetAddress sender) {
    for (String line : reply.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("location")) {
        try {
          URI uri = URI.create(line.substring(colon + 1).trim());
          if ("http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
              && InetAddress.getByName(uri.getHost()).equals(sender)) {
            return Optional.of(new Located(uri, sender));
          }
        } catch (IllegalArgumentException | IOException invalid) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  static Optional<UpnpGateway> describe(URI location, InetAddress router, Duration timeout)
      throws IOException {
    UpnpGateway reader = new UpnpGateway(location, "", router, timeout);
    Document description = parse(reader.send(HttpRequest.newBuilder(location).GET()));
    URI base = location;
    NodeList urlBase = description.getElementsByTagName("URLBase");
    if (urlBase.getLength() > 0 && !urlBase.item(0).getTextContent().isBlank()) {
      base = URI.create(urlBase.item(0).getTextContent().trim());
    }
    NodeList services = description.getElementsByTagName("service");
    for (String wanted : CONNECTION_SERVICES) {
      for (int index = 0; index < services.getLength(); index++) {
        Element service = (Element) services.item(index);
        if (wanted.equals(text(service, "serviceType"))) {
          URI control = base.resolve(text(service, "controlURL"));
          if (control.getHost() != null && InetAddress.getByName(control.getHost()).equals(router)) {
            return Optional.of(new UpnpGateway(control, wanted, router, timeout));
          }
        }
      }
    }
    return Optional.empty();
  }

  InetAddress router() {
    return router;
  }

  /** Asks for the router's public address. */
  InetAddress externalAddress() throws IOException {
    Document reply = action("GetExternalIPAddress", "");
    return InetAddress.getByName(text(reply.getDocumentElement(), "NewExternalIPAddress"));
  }

  /** Asks the router to forward a public UDP port to a port on this machine. */
  void addUdpMapping(int externalPort, InetAddress internalClient, int internalPort,
      Duration lease) throws IOException {
    action("AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + externalPort
        + "</NewExternalPort><NewProtocol>UDP</NewProtocol><NewInternalPort>" + internalPort
        + "</NewInternalPort><NewInternalClient>" + internalClient.getHostAddress()
        + "</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>Synesis Link"
        + "</NewPortMappingDescription><NewLeaseDuration>" + lease.toSeconds()
        + "</NewLeaseDuration>");
  }

  /** Removes a forward this machine asked for. */
  void deleteUdpMapping(int externalPort) throws IOException {
    action("DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + externalPort
        + "</NewExternalPort><NewProtocol>UDP</NewProtocol>");
  }

  private Document action(String name, String arguments) throws IOException {
    String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
        + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + name
        + " xmlns:u=\"" + serviceType + "\">" + arguments + "</u:" + name + "></s:Body></s:Envelope>";
    return parse(send(HttpRequest.newBuilder(controlUrl)
        .header("Content-Type", "text/xml; charset=\"utf-8\"")
        .header("SOAPAction", "\"" + serviceType + "#" + name + "\"")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))));
  }

  private byte[] send(HttpRequest.Builder request) throws IOException {
    try {
      HttpResponse<byte[]> response = http.send(request.timeout(timeout).build(),
          HttpResponse.BodyHandlers.ofByteArray());
      if (response.body().length > MAX_REPLY_BYTES) {
        throw new IOException("router reply is oversized");
      }
      if (response.statusCode() != 200) {
        throw new IOException("router refused the request: HTTP " + response.statusCode()
            + errorCode(response.body()));
      }
      return response.body();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("router request interrupted", interrupted);
    }
  }

  /** Names the UPnP error code of a refused request, such as 718 for a port already in use. */
  private static String errorCode(byte[] body) {
    try {
      return " UPnP " + text(parse(body).getDocumentElement(), "errorCode");
    } catch (IOException | RuntimeException unreadable) {
      return "";
    }
  }

  static Document parse(byte[] xml) throws IOException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setExpandEntityReferences(false);
      factory.setNamespaceAware(false);
      return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    } catch (Exception malformed) {
      throw new IOException("router reply is not valid XML", malformed);
    }
  }

  /** The text of the first element named {@code tag} under {@code parent}, matched ignoring any prefix. */
  static String text(Element parent, String tag) throws IOException {
    NodeList all = parent.getElementsByTagName("*");
    for (int index = 0; index < all.getLength(); index++) {
      String name = all.item(index).getNodeName();
      String local = name.substring(name.indexOf(':') + 1);
      if (local.toLowerCase(Locale.ROOT).equals(tag.toLowerCase(Locale.ROOT))) {
        return all.item(index).getTextContent().trim();
      }
    }
    throw new IOException("router reply has no " + tag);
  }
}
