import assert from 'node:assert/strict';
import test from 'node:test';
import { isIP } from 'node:net';
import { isValidIp, isPrivateIp } from '../src/ip-address.js';

test('literal validation agrees with Node for IPv4 and expanded/compressed/mapped IPv6', () => {
  for (const ip of ['8.8.8.8', '127.0.0.1', '::', '::1', '0:0:0:0:0:0:0:1',
    '::ffff:127.0.0.1', '::ffff:8.8.8.8', '0:0:0:0:0:ffff:7f00:1',
    '2606:4700:4700::1111', '1:2:3:4:5:6:7:8', '1:2:3:4:5:6:192.168.1.1',
    '1:2:3:4:5:6::7:8:9', '1:2:3', ':::', '1::2::3', '1:2:3:4:5:6:7:8::',
    '::ffff:999.1.1.1', '01.2.3.4', 'localhost', '8.8.8.8:80', '[::1]', '']) {
    assert.equal(isValidIp(ip), isIP(ip) !== 0, ip);
  }
});

test('local and reserved detection uses IPv6 bytes and full IPv4 ranges', () => {
  for (const ip of ['0:0:0:0:0:0:0:1', '::ffff:127.0.0.1', '0:0:0:0:0:ffff:7f00:1',
    '00fc:0:0:0:0:0:0:1', 'fc00::1', 'fd00::1', 'fe80::1', 'ff02::1',
    '2001:db8::1', '2001::1', '198.18.255.254', '198.19.255.254']) {
    assert.equal(isPrivateIp(ip), ip !== '00fc:0:0:0:0:0:0:1', ip);
  }
  for (const ip of ['8.8.8.8', '::ffff:8.8.8.8', '2606:4700:4700::1111',
    '2001:db80::1', '198.20.0.1', 'not-an-ip']) assert.equal(isPrivateIp(ip), false, ip);
});
