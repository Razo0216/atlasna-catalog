package com.atlasna.catalog.order.domain;

/** What staff may do when a line can't be packed as ordered. */
public enum SubstitutionPolicy { ALLOW_SIMILAR, REMOVE_ITEM, CALL_ME }
