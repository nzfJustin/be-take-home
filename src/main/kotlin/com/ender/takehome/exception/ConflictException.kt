package com.ender.takehome.exception

/** The request is valid but conflicts with the resource's current state (e.g. charge already paid). */
class ConflictException(message: String) : RuntimeException(message)
