package com.app.service;

import com.app.controller.dto.AuthCreateUserRequest;
import com.app.controller.dto.AuthLoginRequest;
import com.app.controller.dto.AuthResponse;
import com.app.persistence.entity.RoleEntity;
import com.app.persistence.entity.UserEntity;
import com.app.persistence.entity.repository.RoleRepository;
import com.app.persistence.entity.repository.UserRepository;
import com.app.util.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserDetailServiceImpl implements UserDetailsService {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    public Optional<UserEntity> findUsername(String username) {
       return userRepository.findUserEntityByUsername(username);
    }

    public Optional<UserEntity> findById(Long id) {
        return userRepository.findById(id);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity userEntity = userRepository.findUserEntityByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("El usuario " + username + " no existe."));

        List<SimpleGrantedAuthority> authorityList = buildAuthorities(userEntity);

        return new User(userEntity.getUsername(),
                userEntity.getPassword(),
                userEntity.isEnabled(),
                userEntity.isAccountNoExpired(),
                userEntity.isCredentialNoExpired(),
                userEntity.isAccountNoLocked(),
                authorityList);
    }

    public AuthResponse loginUser(AuthLoginRequest authLoginRequest) {
        String username = authLoginRequest.username();
        String password = authLoginRequest.password();

        Authentication authentication = this.authenticate(username, password);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String accessToken = jwtUtils.createToken(authentication);
        AuthResponse authResponse = new AuthResponse(username, "user loged succesfully", accessToken, true);
        return authResponse;
    }

    public Authentication authenticate(String username, String password) {
        UserDetails userDetails = this.loadUserByUsername(username);

        if(userDetails == null) {
            throw new BadCredentialsException("Invalid username or password.");
        }

        if(!passwordEncoder.matches(password, userDetails.getPassword())) {
            throw new BadCredentialsException("Invalid password.");
        }

        return new UsernamePasswordAuthenticationToken(username, userDetails.getPassword(), userDetails.getAuthorities());
    }

    public AuthResponse createUser(AuthCreateUserRequest authCreateUserRequest) {
        String username = authCreateUserRequest.username();
        String password = authCreateUserRequest.password();
        List<String> roleRequest = authCreateUserRequest.roleRequest().roleListName();

        if (userRepository.findUserEntityByUsername(username).isPresent()) {
            throw new IllegalArgumentException("El nombre de usuario ya está en uso.");
        }

        Set<RoleEntity> roleEntitySet = roleRepository.findRoleEntitiesByRoleEnumIn(roleRequest).stream().collect(Collectors.toSet());

        if(roleEntitySet.isEmpty()) {
            throw new IllegalArgumentException("Los roles especificados no existen.");
        }

        UserEntity userEntity = UserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .name(authCreateUserRequest.name())
                .surname(authCreateUserRequest.surname())
                .picture(authCreateUserRequest.picture())
                .bio(authCreateUserRequest.bio())
                .interests(authCreateUserRequest.interests())
                .location(authCreateUserRequest.location())
                .birthday(authCreateUserRequest.birthday())
                .requestedFavours(0)
                .doneFavours(0)
                .roles(roleEntitySet)
                .isEnabled(true)
                .accountNoLocked(true)
                .accountNoExpired(true)
                .credentialNoExpired(true)
                .build();

        UserEntity userCreated = userRepository.save(userEntity);

        List<SimpleGrantedAuthority> authorityList = buildAuthorities(userCreated);

        Authentication authentication = new UsernamePasswordAuthenticationToken(userCreated.getUsername(), userCreated.getPassword(), authorityList);

        String accessToken = jwtUtils.createToken(authentication);

        AuthResponse authResponse = new AuthResponse(userCreated.getUsername(), "Usuario creado exitosamente", accessToken, true);
        return authResponse;
    }

    private List<SimpleGrantedAuthority> buildAuthorities(UserEntity userEntity) {
        List<SimpleGrantedAuthority> authorityList = new ArrayList<>();

        userEntity.getRoles().forEach(role -> authorityList.add(new SimpleGrantedAuthority("ROLE_".concat(role.getRoleEnum().name()))));

        userEntity.getRoles()
                .stream()
                .flatMap(role -> role.getPermissionList().stream())
                .forEach(permission -> authorityList.add(new SimpleGrantedAuthority(permission.getName())));

        return authorityList;
    }

    public List<UserEntity> getUsers() {
        return userRepository.getUsers();
    }

    public void deleteUserByUsername(String username) {
        userRepository.deleteUserEntityByUsername(username);
    }

    public String registerUser(String username, String password, String name, String surname, String picture, String bio, String interests, String location, int requestedFavours, int doneFavours, LocalDate birthday) {
        Optional<UserEntity> existingUser = userRepository.findUserEntityByUsername(username);
        if (existingUser.isPresent()) {
            return "El usuario ya existe";
        }

        String encryptedPassword = passwordEncoder.encode(password);

        UserEntity newUser = UserEntity.builder()
                .username(username)
                .password(encryptedPassword)
                .name(name)
                .surname(surname)
                .picture(picture)
                .bio(bio)
                .interests(interests)
                .location(location)
                .requestedFavours(0)
                .doneFavours(0)
                .birthday(birthday)
                .isEnabled(true)
                .accountNoExpired(true)
                .accountNoLocked(true)
                .credentialNoExpired(true)
                .build();

        userRepository.save(newUser);
        return "Usuario registrado exitosamente";
    }

    @Transactional
    public void userUpdate(UserEntity user, UserEntity selectedContact) {
        UserEntity managedUser = userRepository.findById(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
            
        boolean contactExists = managedUser.getContacts() != null && 
            managedUser.getContacts().stream()
                .anyMatch(contact -> contact.getUsername().equals(selectedContact.getUsername()));
                
        if (contactExists) {
            throw new IllegalStateException("El contacto ya existe en tu lista de contactos");
        }
            
        UserEntity contactToAdd = userRepository.findUserEntityByUsername(selectedContact.getUsername())
            .orElseGet(() -> {
                selectedContact.setId(null);
                return userRepository.save(selectedContact);
            });
            
        if (managedUser.getContacts() == null) {
            managedUser.setContacts(new java.util.HashSet<>());
        }
        
        managedUser.getContacts().add(contactToAdd);
        userRepository.save(managedUser);
    }
    
    public UserEntity updateUser(String username, UserEntity userUpdates) {
        try {
            System.out.println("Buscando usuario: " + username);
            UserEntity existingUser = userRepository.findUserEntityByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + username));
                
            System.out.println("Usuario encontrado. Datos actuales: " + existingUser.toString());
            System.out.println("Datos de actualización recibidos: " + userUpdates.toString());
            
            // Actualizar solo los campos que vienen en userUpdates
            if (userUpdates.getUsername() != null) {
                System.out.println("Actualizando nombre de usuario a: " + userUpdates.getUsername());
                existingUser.setUsername(userUpdates.getUsername());
            }
            if (userUpdates.getName() != null) {
                System.out.println("Actualizando nombre a: " + userUpdates.getName());
                existingUser.setName(userUpdates.getName());
            }
            if (userUpdates.getSurname() != null) {
                System.out.println("Actualizando apellido a: " + userUpdates.getSurname());
                existingUser.setSurname(userUpdates.getSurname());
            }
            if (userUpdates.getBio() != null) {
                System.out.println("Actualizando biografía a: " + userUpdates.getBio());
                existingUser.setBio(userUpdates.getBio());
            }
            if (userUpdates.getPicture() != null) {
                System.out.println("Actualizando imagen de perfil");
                existingUser.setPicture(userUpdates.getPicture());
            }
            if (userUpdates.getInterests() != null) {
                System.out.println("Actualizando intereses a: " + userUpdates.getInterests());
                existingUser.setInterests(userUpdates.getInterests());
            }
            if (userUpdates.getLocation() != null) {
                System.out.println("Actualizando ubicación a: " + userUpdates.getLocation());
                existingUser.setLocation(userUpdates.getLocation());
            }
            if (userUpdates.getBirthday() != null) {
                System.out.println("Actualizando fecha de nacimiento a: " + userUpdates.getBirthday());
                existingUser.setBirthday(userUpdates.getBirthday());
            }
            if (userUpdates.getPassword() != null && !userUpdates.getPassword().isEmpty()) {
                System.out.println("Actualizando contraseña (encriptada)");
                existingUser.setPassword(passwordEncoder.encode(userUpdates.getPassword()));
            }
            
            // Actualizar campos booleanos
            if (userUpdates.isEnabled() != existingUser.isEnabled()) {
                System.out.println("Actualizando estado habilitado a: " + userUpdates.isEnabled());
                existingUser.setEnabled(userUpdates.isEnabled());
            }
            
            // Guardar los cambios en la base de datos
            System.out.println("Guardando cambios en la base de datos...");
            UserEntity updatedUser = userRepository.save(existingUser);
            System.out.println("Usuario actualizado exitosamente: " + updatedUser.toString());
            
            return updatedUser;
            
        } catch (Exception e) {
            System.err.println("Error al actualizar el usuario: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    @Transactional
    public void removeContact(Long userId, Long contactId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado con ID: " + userId));
            
        // Inicializar la lista de contactos si es nula
        if (user.getContacts() == null) {
            user.setContacts(new HashSet<>());
            userRepository.save(user);
            throw new IllegalStateException("El usuario no tiene contactos para eliminar");
        }
        
        if (user.getContacts().isEmpty()) {
            throw new IllegalStateException("El usuario no tiene contactos para eliminar");
        }
        
        // Convertir contactId a long para asegurar la comparación correcta
        long contactIdLong = contactId;
        boolean removed = user.getContacts().removeIf(contact -> 
            contact != null && 
            contact.getId() != null && 
            contact.getId().longValue() == contactIdLong
        );
        
        if (!removed) {
            throw new IllegalStateException("El contacto no existe en la lista de contactos del usuario");
        }
        
        userRepository.save(user);
    }
    
    public UserEntity updateUserById(Long id, UserEntity userUpdates) {
        try {
            System.out.println("Buscando usuario por ID: " + id);
            UserEntity existingUser = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado con ID: " + id));
                
            System.out.println("Usuario encontrado. Datos actuales: " + existingUser.toString());
            System.out.println("Datos de actualización recibidos: " + userUpdates.toString());
            
            // Actualizar solo los campos que vienen en userUpdates
            if (userUpdates.getUsername() != null) {
                System.out.println("Actualizando nombre de usuario a: " + userUpdates.getUsername());
                existingUser.setUsername(userUpdates.getUsername());
            }
            if (userUpdates.getName() != null) {
                System.out.println("Actualizando nombre a: " + userUpdates.getName());
                existingUser.setName(userUpdates.getName());
            }
            if (userUpdates.getSurname() != null) {
                System.out.println("Actualizando apellido a: " + userUpdates.getSurname());
                existingUser.setSurname(userUpdates.getSurname());
            }
            if (userUpdates.getBio() != null) {
                System.out.println("Actualizando biografía a: " + userUpdates.getBio());
                existingUser.setBio(userUpdates.getBio());
            }
            if (userUpdates.getPicture() != null) {
                System.out.println("Actualizando imagen de perfil");
                existingUser.setPicture(userUpdates.getPicture());
            }
            if (userUpdates.getInterests() != null) {
                System.out.println("Actualizando intereses a: " + userUpdates.getInterests());
                existingUser.setInterests(userUpdates.getInterests());
            }
            if (userUpdates.getLocation() != null) {
                System.out.println("Actualizando ubicación a: " + userUpdates.getLocation());
                existingUser.setLocation(userUpdates.getLocation());
            }
            if (userUpdates.getBirthday() != null) {
                System.out.println("Actualizando fecha de nacimiento a: " + userUpdates.getBirthday());
                existingUser.setBirthday(userUpdates.getBirthday());
            }
            if (userUpdates.getPassword() != null && !userUpdates.getPassword().isEmpty()) {
                System.out.println("Actualizando contraseña (encriptada)");
                existingUser.setPassword(passwordEncoder.encode(userUpdates.getPassword()));
            }
            
            // Actualizar campos booleanos
            if (userUpdates.isEnabled() != existingUser.isEnabled()) {
                System.out.println("Actualizando estado habilitado a: " + userUpdates.isEnabled());
                existingUser.setEnabled(userUpdates.isEnabled());
            }
            
            // Guardar los cambios en la base de datos
            System.out.println("Guardando cambios en la base de datos...");
            UserEntity updatedUser = userRepository.save(existingUser);
            System.out.println("Usuario actualizado exitosamente: " + updatedUser.toString());
            
            return updatedUser;
            
        } catch (Exception e) {
            System.err.println("Error al actualizar el usuario: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}
