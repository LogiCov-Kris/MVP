package logicov.workspace

import fabric.rw.*

enum PolicyStatus derives RW { case Pass, Breach, Unavailable, NotConfigured }
