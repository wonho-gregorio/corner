package com.gym.management;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class GymManagementApplicationTests {

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules.of(GymManagementApplication.class).verify();
	}

}
