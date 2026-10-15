package org.synesis.link.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;

/**
 * The endpoint list a compact invitation and its answer carry: how many, which ones, and how they
 * are written.
 *
 * <p>Both links name the addresses one side may be reached on, and the other side cannot tell which
 * of them its network reaches, so both carry several and apply the same bounds and order.
 */
final class CompactEndpoints {

  /** Maximum number of endpoints one link carries. */
  static final int MAX = 8;
  /** Bytes one endpoint takes at most: width byte, IPv6 address, port. */
  static final int MAX_ENCODED_BYTES_EACH = Byte.BYTES + 16 + Short.BYTES;

  private static final Comparator<Candidate> ORDER = Comparator
      .comparingInt((Candidate candidate) -> rank(candidate.type()))
      .thenComparingInt(Candidate::priority)
      .thenComparingInt(candidate -> candidate.address()
          .getAddress().length)
      .thenComparing(candidate -> HexFormat.of()
          .formatHex(candidate.address()
              .getAddress()))
      .thenComparingInt(Candidate::port);

  private CompactEndpoints() {
  }

  /**
   * Selects the endpoints to offer, most promising first. Every kind of candidate is represented
   * before any kind is repeated, so a machine with more addresses than fit still offers a LAN
   * address beside its public ones.
   *
   * @param candidates complete local candidate collection
   * @return one to {@link #MAX} distinct endpoints in preference order
   * @throws IllegalArgumentException if no usable endpoint exists
   */
  static List<Candidate> select(Collection<Candidate> candidates) {
    Objects.requireNonNull(candidates, "candidates");
    record Turn(Candidate candidate, int ofItsType) {
    }
    Map<CandidateType, Integer> seenOfType = new EnumMap<>(CandidateType.class);
    List<Candidate> selected = candidates.stream()
        .filter(Objects::nonNull)
        .filter(candidate -> candidate.type() != CandidateType.RELAY)
        .filter(candidate -> candidate.address()
            .getAddress().length == 4 || candidate.address()
            .getAddress().length == 16)
        .sorted(ORDER)
        .filter(distinct())
        .map(candidate -> new Turn(candidate,
            seenOfType.merge(candidate.type(), 1, Integer::sum)))
        .sorted(Comparator.comparingInt(Turn::ofItsType))
        .map(Turn::candidate)
        .limit(MAX)
        .toList();
    if (selected.isEmpty()) {
      throw new IllegalArgumentException("no usable compact endpoint");
    }
    return selected;
  }

  /**
   * Checks a list a link is built from: one to {@link #MAX} distinct, directly dialable endpoints.
   *
   * @param values endpoints in preference order
   * @return the endpoints with their priority cleared, which the encoding does not carry
   * @throws IllegalArgumentException if the list breaks a bound
   */
  static List<Candidate> validate(List<Candidate> values) {
    Objects.requireNonNull(values, "endpoints");
    if (values.isEmpty() || values.size() > MAX) {
      throw new IllegalArgumentException("a compact link carries 1 to " + MAX + " endpoints");
    }
    List<Candidate> endpoints = values.stream()
        .map(CompactEndpoints::validateOne)
        .toList();
    if (!endpoints.stream().allMatch(distinct())) {
      throw new IllegalArgumentException("compact endpoints must be distinct");
    }
    return endpoints;
  }

  /**
   * Writes a count byte and, for each endpoint, its address width (4 or 16), address, and port.
   */
  static void write(DataOutputStream output, List<Candidate> endpoints) throws IOException {
    output.writeByte(endpoints.size());
    for (Candidate endpoint : endpoints) {
      byte[] address = endpoint.address().getAddress();
      output.writeByte(address.length);
      output.write(address);
      output.writeShort(endpoint.port());
    }
  }

  /**
   * Reads what {@link #write} wrote. An IPv6 address reads back as an IPv6 candidate and an IPv4
   * one as a manual candidate, because the encoding does not carry the kind.
   *
   * @throws IOException if the count, a width, or a port is outside the layout
   */
  static List<Candidate> read(DataInputStream input) throws IOException {
    int count = input.readUnsignedByte();
    if (count == 0 || count > MAX) {
      throw new IOException("invalid compact endpoint count");
    }
    List<Candidate> endpoints = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      int addressLength = input.readUnsignedByte();
      if (addressLength != 4 && addressLength != 16) {
        throw new IOException("compact endpoint must be IPv4 or IPv6");
      }
      if (input.available() < addressLength) {
        throw new IOException("truncated compact endpoint");
      }
      byte[] address = input.readNBytes(addressLength);
      int port = input.readUnsignedShort();
      if (port == 0) {
        throw new IOException("compact endpoint port is zero");
      }
      endpoints.add(new Candidate(addressLength == 16 ? CandidateType.IPV6 : CandidateType.MANUAL,
          InetAddress.getByAddress(address), port, 0));
    }
    return endpoints;
  }

  private static Candidate validateOne(Candidate value) {
    Objects.requireNonNull(value, "endpoint");
    if (value.type() == CandidateType.RELAY || value.address()
        .isAnyLocalAddress() || value.address()
        .isMulticastAddress()
        || value.address() instanceof Inet6Address ipv6 && ipv6.isLinkLocalAddress()) {
      throw new IllegalArgumentException("compact endpoint is not directly usable");
    }
    int length = value.address()
        .getAddress().length;
    if (length != 4 && length != 16) {
      throw new IllegalArgumentException("compact endpoint must be IPv4 or IPv6");
    }
    return new Candidate(value.type(), value.address(), value.port(), 0);
  }

  /** A stateful filter that passes each address and port once. */
  private static Predicate<Candidate> distinct() {
    Set<String> seen = new HashSet<>();
    return candidate -> seen.add(HexFormat.of().formatHex(candidate.address().getAddress())
        + ":" + candidate.port());
  }

  private static int rank(CandidateType type) {
    return switch (type) {
      case SERVER_REFLEXIVE -> 0;
      case IPV6 -> 1;
      case MANUAL -> 2;
      case MAPPED_IPV4 -> 3;
      case LAN -> 4;
      case RELAY -> 5;
    };
  }
}
