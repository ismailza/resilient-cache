/*
 * Copyright 2026 Ismail ZAHIR
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.untrusted;

/**
 * A class outside the trusted packages, standing in for a deserialization gadget: a Redis entry naming
 * it must never be instantiated.
 */
public class Outsider {

    /** Set when Jackson instantiates the class — what a gadget's side effect would be. */
    public static volatile boolean instantiated;

    private String name;

    public Outsider() {
        instantiated = true;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
