package com.subash.atti.data.model

/** Another ATTI user found nearby. [endpointId] is unique; two people can share a [name]. */
data class Peer(val endpointId: String, val name: String)
