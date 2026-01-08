package local.oss.chronicle.application

import local.oss.chronicle.injection.components.AppComponent

class Injector private constructor() {
    companion object {
        fun get(): AppComponent = ChronicleApplication.get().appComponent
    }
}
