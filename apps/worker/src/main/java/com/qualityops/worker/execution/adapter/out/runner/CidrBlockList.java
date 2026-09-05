package com.qualityops.worker.execution.adapter.out.runner;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Reserved / dangerous IP ranges the JDK InetAddress predicates do not cover. */
final class CidrBlockList {

    private record Cidr(BigInteger network, int prefix, int bits) {}

    private static final List<Cidr> BLOCKS = List.of(
        cidr("100.64.0.0", 10),     // CGNAT
        cidr("192.0.0.0", 24),      // IETF protocol assignments
        cidr("198.18.0.0", 15),     // benchmarking
        cidr("240.0.0.0", 4),       // reserved / class E
        cidr("fc00::", 7)           // IPv6 ULA
    );

    boolean isBlocked(InetAddress address) {
        byte[] raw = unwrapV4Mapped(address.getAddress());
        BigInteger value = new BigInteger(1, raw);
        int bits = raw.length * 8;
        for (Cidr c : BLOCKS) {
            if (c.bits() != bits) {
                continue;
            }
            BigInteger mask = maskOf(c.prefix(), bits);
            if (value.and(mask).equals(c.network().and(mask))) {
                return true;
            }
        }
        return false;
    }

    private static byte[] unwrapV4Mapped(byte[] raw) {
        if (raw.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                return new byte[]{raw[12], raw[13], raw[14], raw[15]};
            }
        }
        return raw;
    }

    private static Cidr cidr(String addr, int prefix) {
        try {
            byte[] raw = InetAddress.getByName(addr).getAddress();
            return new Cidr(new BigInteger(1, raw), prefix, raw.length * 8);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger maskOf(int prefix, int bits) {
        return BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            .shiftRight(bits - prefix).shiftLeft(bits - prefix);
    }
}
