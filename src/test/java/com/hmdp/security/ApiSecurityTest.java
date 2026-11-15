package com.hmdp.security;

import com.hmdp.controller.ShopController;
import com.hmdp.controller.UploadController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IShopService;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiSecurityTest {
    @TempDir Path root;
    private static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10,0,0,0,0};
    @AfterEach void clear() { UserHolder.removeUser(); }
    private void user(long id) { UserDTO u = new UserDTO(); u.setId(id); UserHolder.saveUser(u); }

    @Test void publicReadAllowedButWritesAndFileDeletionRequireLogin() throws Exception {
        LoginInterceptor guard = new LoginInterceptor();
        assertTrue(guard.preHandle(new MockHttpServletRequest("GET", "/shop/1"), new MockHttpServletResponse(), new Object()));
        assertTrue(guard.preHandle(new MockHttpServletRequest("GET", "/voucher/list/1"), new MockHttpServletResponse(), new Object()));
        for (String path : new String[]{"/shop", "/voucher/seckill", "/upload/blog"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(guard.preHandle(new MockHttpServletRequest("POST", path), response, new Object()));
            assertEquals(401, response.getStatus());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(guard.preHandle(new MockHttpServletRequest("GET", "/upload/blog/delete"), response, new Object()));
        assertEquals(401, response.getStatus());
        user(1);
        assertTrue(guard.preHandle(new MockHttpServletRequest("POST", "/voucher/seckill"), new MockHttpServletResponse(), new Object()));
    }

    @Test void actualControllerRejectsAnonymousWriteBeforeCallingService() throws Exception {
        IShopService service = mock(IShopService.class);
        ShopController controller = new ShopController();
        ReflectionTestUtils.setField(controller, "shopService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new LoginInterceptor()).build();
        mvc.perform(post("/shop").contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.queryByID(1L)).thenReturn(Result.ok());
        mvc.perform(get("/shop/1")).andExpect(status().isOk());
        verify(service).queryByID(1L);
    }

    @Test void uploadAndDeleteRespectOwnershipAndExistingFrontendPrefix() throws Exception {
        UploadController controller = new UploadController(root.toString());
        user(12);
        Result result = controller.uploadImage(new MockMultipartFile("file", "test.png", "image/png", PNG));
        assertTrue(result.getSuccess());
        String name = (String) result.getData();
        Path file = root.resolve(name.substring(1));
        assertTrue(Files.isRegularFile(file));
        user(13);
        assertFalse(controller.deleteBlogImg("/imgs" + name).getSuccess());
        assertTrue(Files.exists(file));
        user(12);
        assertTrue(controller.deleteBlogImg("/imgs" + name).getSuccess());
        assertFalse(Files.exists(file));
    }

    @Test void rejectsTraversalLegacyAndInvalidImageBytes() throws Exception {
        UploadController controller = new UploadController(root.toString());
        user(12);
        Path victim = root.resolve("victim.txt"); Files.write(victim, new byte[]{1});
        assertFalse(controller.deleteBlogImg("/imgs/../victim.txt").getSuccess());
        assertFalse(controller.deleteBlogImg("/blogs/0/0/old.jpg").getSuccess());
        assertTrue(Files.exists(victim));
        assertFalse(controller.uploadImage(new MockMultipartFile("file", "bad.png", "image/png", "not an image".getBytes("UTF-8"))).getSuccess());
        assertFalse(controller.uploadImage(new MockMultipartFile("file", "bad.svg", "image/svg+xml", PNG)).getSuccess());
    }

    @Test void rejectsSymlinkWithinUploadTree() throws Exception {
        Path outside = Files.createTempDirectory("hmdp-security-test-");
        try {
            Path blogs = root.resolve("blogs");
            try { Files.createSymbolicLink(blogs, outside); }
            catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException e) { return; }
            user(12);
            UploadController controller = new UploadController(root.toString());
            assertThrows(java.io.IOException.class, () -> controller.uploadImage(new MockMultipartFile("file", "test.png", "image/png", PNG)));
        } finally { Files.deleteIfExists(outside); }
    }
}
