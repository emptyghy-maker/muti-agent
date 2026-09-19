package com.ghy.mutiagent.model;

import java.math.BigDecimal;

/**
 * 住宿预订（S05）：第几晚（1 起）、酒店、房间数、每晚房价。
 * nightlyRate 为 null 表示房价未核实；行程 days 天默认住第 1..days-1 晚，离店日不产生夜次。
 */
public record StayBooking(int nightIndex, long hotelId, int rooms, BigDecimal nightlyRate) {
}
