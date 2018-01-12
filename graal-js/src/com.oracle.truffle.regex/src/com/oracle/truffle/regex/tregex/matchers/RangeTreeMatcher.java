/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.tregex.matchers;

import com.oracle.truffle.api.CompilerDirectives;

/**
 * Character range matcher using a left-balanced tree of ranges.
 */
public final class RangeTreeMatcher extends ProfiledCharMatcher {

    public static RangeTreeMatcher fromRanges(boolean invert, char[] ranges) {
        char[] tree = new char[ranges.length];
        buildTree(tree, 0, ranges, 0, ranges.length / 2);
        return new RangeTreeMatcher(invert, tree);
    }

    /**
     * Adapted version of the algorithm given by J. Andreas Baerentzen in
     * <a href="http://www2.imm.dtu.dk/pubdb/views/edoc_download.php/2535/pdf/imm2535.pdf">"On
     * Left-balancing Binary Trees"</a>.
     * 
     * @param tree The array that will hold the tree. Its size must be equal to that of the
     *            parameter "ranges".
     * @param curTreeElement Index of the current tree node to be computed. Note that every tree
     *            element takes up two array slots, since every node corresponds to a character
     *            range.
     * @param ranges Sorted array of character ranges, in the form [lower bound of range 0, higher
     *            bound of range 0, lower bound of range 1, higher bound of range 1, ...]
     * @param offset Starting index of the current part of the list that shall be converted to a
     *            subtree.
     * @param nRanges Number of _ranges_ in the current part of the list that shall be converted to
     *            a subtree. Again, note that every range takes up two array slots!
     */
    private static void buildTree(char[] tree, int curTreeElement, char[] ranges, int offset, int nRanges) {
        if (nRanges == 0) {
            return;
        }
        if (nRanges == 1) {
            tree[curTreeElement] = ranges[offset];
            tree[curTreeElement + 1] = ranges[offset + 1];
            return;
        }
        int nearestPowerOf2 = Integer.highestOneBit(nRanges);
        int remainder = nRanges - (nearestPowerOf2 - 1);
        int nLeft;
        int nRight;
        if (remainder <= nearestPowerOf2 / 2) {
            nLeft = (nearestPowerOf2 - 2) / 2 + remainder;
            nRight = (nearestPowerOf2 - 2) / 2;
        } else {
            nLeft = nearestPowerOf2 - 1;
            nRight = remainder - 1;
        }
        int median = offset + nLeft * 2;
        tree[curTreeElement] = ranges[median];
        tree[curTreeElement + 1] = ranges[median + 1];
        buildTree(tree, leftChild(curTreeElement), ranges, offset, nLeft);
        buildTree(tree, rightChild(curTreeElement), ranges, median + 2, nRight);
    }

    private static int leftChild(int i) {
        return (i * 2) + 2;
    }

    private static int rightChild(int i) {
        return (i * 2) + 4;
    }

    @CompilerDirectives.CompilationFinal(dimensions = 1) private final char[] tree;

    private RangeTreeMatcher(boolean invert, char[] tree) {
        super(invert);
        this.tree = tree;
    }

    @Override
    public boolean matchChar(char c) {
        int i = 0;
        while (i < tree.length) {
            final char lo = tree[i];
            final char hi = tree[i + 1];
            if (lo <= c) {
                if (hi >= c) {
                    return true;
                } else {
                    i = rightChild(i);
                }
            } else {
                i = leftChild(i);
            }
        }
        return false;
    }

    @Override
    @CompilerDirectives.TruffleBoundary
    public String toString() {
        return modifiersToString() + MatcherBuilder.rangesToString(tree);
    }
}