package io.bluetape4k.exposed.examples.modulithinvalid.shipping

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@ApplicationModule(allowedDependencies = ["orders :: events"])
@PackageInfo
class ModuleMetadata
